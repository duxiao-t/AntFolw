#!/usr/bin/env node
/**
 * AFBK1 备份归档的**流式**解密（恢复脚本的首选工具，`BackupDecrypt.java` 作无 Node 环境的兜底）。
 *
 * 为什么需要两个实现：JDK 的 AES-GCM **解密**会把整段密文缓冲进内存
 * （`GaloisCounterMode$GCMDecrypt.doUpdate` → ByteArrayOutputStream），
 * `CipherInputStream` + AEAD 根本不流式；峰值 ≈ 2× 归档，1.3GB 归档在 512MB 堆上直接 OOM，
 * 即便堆够也要 20s+（实测）。OpenSSL（Node 的 crypto 走它）则是真流式：
 * 同一份 1.24GiB 归档 **1.2s** 解完、内存恒定。
 *
 * 格式：5B magic "AFBK1" + 4B(大端) salt 长度(=16) + salt + 12B IV + 密文(尾部 16B 为 GCM tag)
 * 密钥派生：PBKDF2-HMAC-SHA256(secret, salt, 210000) → 256bit
 *
 * 用法：BACKUP_ENCRYPTION_SECRET=... node BackupDecrypt.mjs IN.afbackup OUT.zip
 * 退出码：0 成功；2 参数/环境问题；3 认证失败（密钥不对或归档损坏）
 */
import { createDecipheriv, pbkdf2Sync } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { open, stat } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';

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

const MAGIC = Buffer.from('AFBK1', 'ascii');
const TAG_LEN = 16;
const size = (await stat(input)).size;
const fd = await open(input, 'r');
try {
  const head = Buffer.alloc(9);
  await fd.read(head, 0, 9, 0);
  if (!head.subarray(0, 5).equals(MAGIC)) {
    console.error('不是 AFBK1 归档（magic 不匹配）');
    process.exit(3);
  }
  const saltLength = head.readInt32BE(5);
  if (saltLength !== 16) {
    console.error(`salt 长度异常：${saltLength}`);
    process.exit(3);
  }
  const salt = Buffer.alloc(saltLength);
  await fd.read(salt, 0, saltLength, 9);
  const iv = Buffer.alloc(12);
  await fd.read(iv, 0, 12, 9 + saltLength);
  const tag = Buffer.alloc(TAG_LEN);
  await fd.read(tag, 0, TAG_LEN, size - TAG_LEN);

  const dataStart = 9 + saltLength + 12;
  const dataEnd = size - TAG_LEN - 1; // 含首不含尾
  if (dataEnd < dataStart) {
    console.error('归档过短，缺少密文体');
    process.exit(3);
  }

  const key = pbkdf2Sync(secret, salt, 210_000, 32, 'sha256');
  const decipher = createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  try {
    await pipeline(createReadStream(input, { start: dataStart, end: dataEnd }), decipher, createWriteStream(output));
  } catch (error) {
    // GCM tag 校验失败会在这里抛（密钥不对 / 归档被截断或篡改）
    console.error(`解密失败（认证不通过）：${error.message}`);
    process.exit(3);
  }
} finally {
  await fd.close();
}
console.log(`解密完成：${input} → ${output}`);
