#!/usr/bin/env node
/**
 * 备份归档的**流式**解密（恢复脚本的首选工具，`BackupDecrypt.java` 作无 Node 环境的兜底）。
 *
 * 支持两种格式：
 *   AFBK1（旧）：5B "AFBK1" | int saltLen(16) | salt | 12B iv | 单条 AES-GCM 密文（尾 16B tag）
 *   AFBK2（新）：5B "AFBK2" | int saltLen(16) | salt | 重复 [ int ctLen | 12B iv | ctLen 字节 ] | int 0
 * 密钥：PBKDF2-HMAC-SHA256(secret, salt, 210000) → 256bit
 *
 * 为什么 AFBK2 要分块：JDK 的 GCM 解密只在 doFinal 吐明文（update 恒空），1GB 归档要整包驻留内存；
 * OpenSSL（Node 的 crypto）虽然能流式，但旧格式仍是"单条密文"，一块失败就得整包重来。
 * 分块后每块独立 IV+tag，逐块解、内存恒定，且能定位坏块。
 *
 * 与 `backend/src/main/java/com/antflow/backup/BackupArchiveCipher.java`、`BackupDecrypt.java`
 * 是同一份格式的三处实现，改格式时必须同步。
 *
 * 用法：BACKUP_ENCRYPTION_SECRET=... node BackupDecrypt.mjs IN.afbackup OUT.zip
 * 退出码：0 成功；2 参数/环境问题；3 认证失败（密钥不对或归档损坏/截断）
 */
import { createDecipheriv, pbkdf2Sync } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { open, stat } from 'node:fs/promises';
import { once } from 'node:events';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: BACKUP_ENCRYPTION_SECRET=... node BackupDecrypt.mjs IN.afbackup OUT.zip');
  process.exit(2);
}
const secret = process.env.BACKUP_ENCRYPTION_SECRET;
if (!secret || secret.length < 32) {
  console.error('BACKUP_ENCRYPTION_SECRET 缺失或短于 32 字符');
  process.exit(2);
}

const SALT = 16;
const IV = 12;
const TAG = 16;
const info = await stat(input);
const size = info.size;
const fd = await open(input, 'r');
const out = createWriteStream(output);

const readAt = async (buffer, length, position) => {
  const { bytesRead } = await fd.read(buffer, 0, length, position);
  if (bytesRead !== length) throw new Error(`归档被截断：期望 ${length} 字节，实际 ${bytesRead}`);
};

const write = async (buffer) => {
  if (!out.write(buffer)) await once(out, 'drain');
};

try {
  const magic = Buffer.alloc(5);
  await readAt(magic, 5, 0);
  const saltLength = Buffer.alloc(4);
  await readAt(saltLength, 4, 5);
  const saltLen = saltLength.readInt32BE(0);
  if (saltLen !== SALT) throw new Error(`salt 长度异常：${saltLen}`);
  const salt = Buffer.alloc(saltLen);
  await readAt(salt, saltLen, 9);
  const key = pbkdf2Sync(secret, salt, 210_000, 32, 'sha256');

  if (magic.toString('ascii') === 'AFBK2') {
    // 分块：逐块读长度→IV→密文，独立校验 tag
    let offset = 9 + saltLen;
    for (;;) {
      if (offset + 4 > size) throw new Error('归档被截断：缺少块长度');
      const lenRaw = Buffer.alloc(4);
      await readAt(lenRaw, 4, offset);
      const length = lenRaw.readInt32BE(0);
      offset += 4;
      if (length === 0) break;
      const iv = Buffer.alloc(IV);
      await readAt(iv, IV, offset);
      offset += IV;
      const cipherText = Buffer.alloc(length);
      await readAt(cipherText, length, offset);
      offset += length;
      const decipher = createDecipheriv('aes-256-gcm', key, iv);
      decipher.setAuthTag(cipherText.subarray(length - TAG));
      const plain = Buffer.concat([
        decipher.update(cipherText.subarray(0, length - TAG)),
        decipher.final(),
      ]);
      await write(plain);
    }
  } else if (magic.toString('ascii') === 'AFBK1') {
    // 旧格式：单条密文（尾 16B tag），一次流式解
    const iv = Buffer.alloc(IV);
    await readAt(iv, IV, 9 + saltLen);
    const dataStart = 9 + saltLen + IV;
    if (size - TAG - 1 < dataStart) throw new Error('归档过短，缺少密文体');
    const tag = Buffer.alloc(TAG);
    await readAt(tag, TAG, size - TAG);
    const decipher = createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(tag);
    // 旧格式用 pipeline：内核级背压 + 大块读写，1.3GB 只要几秒；
    // 逐块 await write 会退化到 1 分半（实测），恢复演练等不起。
    const { createReadStream } = await import('node:fs');
    const { pipeline } = await import('node:stream/promises');
    await pipeline(createReadStream(input, { start: dataStart, end: size - TAG - 1 }), decipher, out);
    await fd.close();
    console.log(`解密完成：${input} → ${output}`);
    process.exit(0);
  } else {
    throw new Error(`不是 AFBK 归档（magic=${magic.toString('ascii')}）`);
  }

  await new Promise((resolve, reject) => out.end((error) => (error ? reject(error) : resolve())));
  await fd.close();
  console.log(`解密完成：${input} → ${output}`);
} catch (error) {
  out.destroy();
  await fd.close().catch(() => {});
  console.error(`解密失败：${error.message}`);
  process.exit(3);
}
