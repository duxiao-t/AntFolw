import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.Arrays;

/**
 * 恢复脚本里的解密工具：AFBK1（magic + 16B salt + 12B IV + 单条 AES-GCM 密文）→ 明文 zip。
 *
 * 为什么整包读入再 doFinal，而不是 CipherInputStream + transferTo：
 *   JDK 的 GCM **解密**在 update() 阶段不产出任何明文（只缓冲），全部在 doFinal() 一次吐出。
 *   用 CipherInputStream 时它内部按 512 字节循环调 update，1GB 归档会变成几百万次小调用，
 *   实测 CPU 烧十几分钟、目标文件始终 0 字节（看起来像卡死，排查时踩过）。
 *   整包 doFinal 是**纯 JDK 前提下**的最优做法：峰值堆 ≈ 归档 × 2.6（密文 + 明文），
 *   所以调用方必须按归档体积给 -Xmx，脚本里已按体积算，堆不够会在入口直接失败。
 *
 * 注意：真正流式的是 OpenSSL 系实现（`backup/BackupDecrypt.mjs`，node 走它，内存恒定、
 * 同盘 1.3GB 实测 3s），恢复脚本优先用 node；这个 Java 版是没有 node 时的兜底。
 * 大归档（>4GB）建议后续改成分块格式（每块独立 IV+tag，AFBK2），那才是格式级的流式。
 */
class BackupDecrypt {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("usage: BackupDecrypt INPUT OUTPUT");
    String secret = System.getenv("BACKUP_ENCRYPTION_SECRET");
    if (secret == null || secret.length() < 32) throw new IllegalArgumentException("BACKUP_ENCRYPTION_SECRET is required");
    // 先算账再动手：AFBK1 需要整包驻留（密文 + 明文 ≈ 2.6×），堆不够时必须立刻失败。
    // 否则表现是"跑十几分钟、目标文件恒 0 字节"，极易被误判成卡死或环境问题（排查时踩过）。
    long archiveBytes = Files.size(Path.of(args[0]));
    long needed = archiveBytes * 3;
    long maxHeap = Runtime.getRuntime().maxMemory();
    if (maxHeap < needed) {
      throw new IllegalStateException(String.format(
          "堆不足：该归档需要约 %dMB，当前 maxMemory=%dMB。请用 -Xmx%dm 重试，"
              + "或改用流式工具 backup/BackupDecrypt.mjs（node，恒定内存）",
          needed >> 20, maxHeap >> 20, (needed >> 20) + 512));
    }
    try (InputStream input = new BufferedInputStream(Files.newInputStream(Path.of(args[0])), 1 << 20)) {
      if (!Arrays.equals(input.readNBytes(5), "AFBK1".getBytes(StandardCharsets.US_ASCII)))
        throw new IllegalArgumentException("invalid backup header");
      int saltLength = ByteBuffer.wrap(input.readNBytes(4)).getInt();
      if (saltLength != 16) throw new IllegalArgumentException("invalid backup salt");
      byte[] salt = input.readNBytes(saltLength), iv = input.readNBytes(12);
      PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, 210_000, 256);
      byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] plain = cipher.doFinal(input.readAllBytes());
      Files.write(Path.of(args[1]), plain);
    }
  }
}
