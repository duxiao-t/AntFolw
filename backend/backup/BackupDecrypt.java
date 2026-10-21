import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.Arrays;

/**
 * 恢复脚本里的解密工具：把 .afbackup 解成明文 zip。支持两种格式。
 *
 * <pre>
 * AFBK1（旧）：5B "AFBK1" | int saltLen(16) | salt | 12B iv | 单条 AES-GCM 密文（尾 16B tag）
 * AFBK2（新）：5B "AFBK2" | int saltLen(16) | salt | 重复 [ int ctLen | 12B iv | ctLen 字节（尾 16B tag） ] | int 0
 * 密钥：PBKDF2-HMAC-SHA256(secret, salt, 210000) → 256bit
 * </pre>
 *
 * <p>AFBK2 是分块的，所以能真流式（逐块读、解、写）；AFBK1 只能整包：
 * JDK 的 GCM **解密**在 update() 阶段不吐明文，全部缓冲到 doFinal（实测 1.3GB 归档要整包驻留内存）。
 * 因此下面的堆守卫只对 v1 生效——v2 用 -Xmx256m 也能解几个 GB 的归档。
 *
 * <p>与 {@code backup/BackupArchiveCipher.java}、{@code BackupDecrypt.mjs} 是同一份格式的三处实现，
 * 改格式时必须同步（本文件刻意不依赖项目其他类，恢复时可以单独拷走运行）。
 */
class BackupDecrypt {
  private static final int SALT_LENGTH = 16;
  private static final int IV_LENGTH = 12;
  private static final int TAG_BITS = 128;

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("usage: BackupDecrypt INPUT OUTPUT");
    String secret = System.getenv("BACKUP_ENCRYPTION_SECRET");
    if (secret == null || secret.length() < 32) {
      throw new IllegalArgumentException("BACKUP_ENCRYPTION_SECRET is required (>=32 chars)");
    }
    try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(args[0])), 1 << 20);
         OutputStream out = new BufferedOutputStream(Files.newOutputStream(Path.of(args[1])), 1 << 20)) {
      byte[] magic = in.readNBytes(5);
      if (Arrays.equals(magic, "AFBK1".getBytes(StandardCharsets.US_ASCII))) {
        decryptLegacy(in, out, secret.toCharArray(), Files.size(Path.of(args[0])));
      } else if (Arrays.equals(magic, "AFBK2".getBytes(StandardCharsets.US_ASCII))) {
        decryptChunked(in, out, secret.toCharArray());
      } else {
        throw new IllegalArgumentException("invalid backup header");
      }
    }
  }

  /** AFBK2：逐块解，内存恒定。任何一块 tag 失败立即抛错（等于整包被篡改/密钥不对）。 */
  private static void decryptChunked(InputStream in, OutputStream out, char[] secret) throws Exception {
    int saltLength = readInt(in);
    if (saltLength != SALT_LENGTH) throw new IllegalArgumentException("invalid backup salt");
    byte[] salt = readFully(in, saltLength);
    SecretKey key = deriveKey(secret, salt);
    while (true) {
      int length = readInt(in);
      if (length == 0) return;
      byte[] iv = readFully(in, IV_LENGTH);
      byte[] cipherText = readFully(in, length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      out.write(cipher.doFinal(cipherText));
    }
  }

  /**
   * AFBK1：单条 GCM，JDK 只能整包 doFinal（update 不吐明文）。
   * 先按体积算账：堆不够就立刻失败并给出可执行建议，而不是跑十几分钟、目标文件恒 0 字节
   * （排查时踩过：CipherInputStream 的 512B 小缓冲会把 1GB 变成几百万次 update）。
   */
  private static void decryptLegacy(InputStream in, OutputStream out, char[] secret, long archiveBytes)
      throws Exception {
    long needed = archiveBytes * 3;
    long maxHeap = Runtime.getRuntime().maxMemory();
    if (maxHeap < needed) {
      throw new IllegalStateException(String.format(
          "堆不足：该 AFBK1 归档需要约 %dMB，当前 maxMemory=%dMB。请用 -Xmx%dm 重试",
          needed >> 20, maxHeap >> 20, (needed >> 20) + 512));
    }
    int saltLength = readInt(in);
    if (saltLength != SALT_LENGTH) throw new IllegalArgumentException("invalid backup salt");
    byte[] salt = readFully(in, saltLength);
    byte[] iv = readFully(in, IV_LENGTH);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, deriveKey(secret, salt), new GCMParameterSpec(TAG_BITS, iv));
    out.write(cipher.doFinal(in.readAllBytes()));
  }

  private static SecretKey deriveKey(char[] secret, byte[] salt) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(secret, salt, 210_000, 256);
    byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    return new SecretKeySpec(key, "AES");
  }

  private static int readInt(InputStream in) throws IOException {
    byte[] raw = in.readNBytes(4);
    if (raw.length != 4) throw new EOFException("truncated backup: missing chunk length");
    return ByteBuffer.wrap(raw).getInt();
  }

  private static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] raw = in.readNBytes(length);
    if (raw.length != length) throw new EOFException("truncated backup: expected " + length + " bytes");
    return raw;
  }
}
