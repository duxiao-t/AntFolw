package com.antflow.backup;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 备份归档的加密/解密格式（写 v2、读 v1+v2）。
 *
 * <pre>
 * AFBK1（旧，只读兼容）：5B "AFBK1" | int saltLen(=16) | salt | 12B iv | 单条 AES-GCM 密文（尾部 16B 为 tag）
 * AFBK2（新，分块）：    5B "AFBK2" | int saltLen(=16) | salt | 重复 [ int ctLen | 12B iv | ctLen 字节（尾部 16B 为 tag） ] | int 0
 * 密钥：PBKDF2-HMAC-SHA256(secret, salt, 210000) → 256bit；每块独立 IV + 独立 tag。
 * </pre>
 *
 * <p>为什么必须分块（v1 的教训）：JDK 的 **GCM 解密**在 {@code update()} 阶段不吐明文
 * （全部缓冲到 {@code doFinal}），所以 v1 解一个 1.3GB 归档要整包驻留内存（实测 2.6× 归档），
 * 512MB 堆直接 OOM、默认堆表现为"跑十几分钟输出 0 字节"。分块后每块只有 4MB，内存恒定。
 *
 * <p>为什么每块独立 IV：GCM 的 IV 绝不能在同一把密钥下复用；分块天然要求每块新 IV。
 * 分块也顺带给出了"流式中断即失败"的语义——任何一块 tag 校验失败立刻抛错。
 *
 * <p>工具侧（{@code backend/backup/BackupDecrypt.java}、{@code .mjs}）是同一份格式的独立实现
 * （它们要能脱离 Spring 单独运行），改动本类时三处必须同步。
 */
public final class BackupArchiveCipher {
    public static final byte[] MAGIC_V1 = "AFBK1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] MAGIC_V2 = "AFBK2".getBytes(StandardCharsets.US_ASCII);

    static final int SALT_LENGTH = 16;
    static final int IV_LENGTH = 12;
    static final int TAG_BITS = 128;
    /** 4MB 一块：足够摊薄 PBKDF2/GC 开销，又能把解密峰值内存压到个位数 MB。 */
    static final int CHUNK_BYTES = 4 << 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupArchiveCipher() { }

    /** 写出 AFBK2 头部并返回一个"按块加密"的流；关闭时写块结束标记。 */
    public static OutputStream encrypt(OutputStream out, char[] secret) throws IOException {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        out.write(MAGIC_V2);
        out.write(ByteBuffer.allocate(4).putInt(SALT_LENGTH).array());
        out.write(salt);
        try {
            return new ChunkedCipherOutputStream(out, deriveKey(secret, salt));
        } catch (GeneralSecurityException error) {
            throw new IOException("无法初始化备份加密", error);
        }
    }

    /** 读 AFBK1/AFBK2 并流式解出明文。 */
    public static void decrypt(InputStream in, OutputStream out, char[] secret) throws IOException {
        byte[] magic = in.readNBytes(5);
        if (Arrays.equals(magic, MAGIC_V1)) {
            decryptLegacy(in, out, secret);
            return;
        }
        if (!Arrays.equals(magic, MAGIC_V2)) throw new IOException("不是 AFBK 归档（magic 不匹配）");
        int saltLength = readInt(in);
        if (saltLength != SALT_LENGTH) throw new IOException("归档 salt 长度异常：" + saltLength);
        byte[] salt = readFully(in, saltLength);
        SecretKey key;
        try {
            key = deriveKey(secret, salt);
        } catch (GeneralSecurityException error) {
            throw new IOException("无法派生备份密钥", error);
        }
        while (true) {
            int length = readInt(in);
            if (length == 0) return;
            byte[] iv = readFully(in, IV_LENGTH);
            byte[] cipherText = readFully(in, length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                out.write(cipher.doFinal(cipherText));
            } catch (GeneralSecurityException error) {
                // 密钥不对 / 归档被截断或篡改，都会落到这里
                throw new IOException("备份块解密失败（认证不通过）", error);
            }
        }
    }

    /** v1：单条 GCM，JDK 只能整包 doFinal——大归档需要大堆，仅用于读历史备份。 */
    private static void decryptLegacy(InputStream in, OutputStream out, char[] secret)
            throws IOException {
        int saltLength = readInt(in);
        if (saltLength != SALT_LENGTH) throw new IOException("归档 salt 长度异常：" + saltLength);
        byte[] salt = readFully(in, saltLength);
        byte[] iv = readFully(in, IV_LENGTH);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(secret, salt),
                new GCMParameterSpec(TAG_BITS, iv));
            out.write(cipher.doFinal(in.readAllBytes()));
        } catch (GeneralSecurityException error) {
            throw new IOException("备份解密失败（认证不通过）", error);
        }
    }

    private static SecretKey deriveKey(char[] secret, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, 210_000, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            .getEncoded();
        return new SecretKeySpec(key, "AES");
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] raw = in.readNBytes(4);
        if (raw.length != 4) throw new EOFException("归档被截断：缺少块长度");
        return ByteBuffer.wrap(raw).getInt();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] raw = in.readNBytes(length);
        if (raw.length != length) throw new EOFException("归档被截断：期望 " + length + " 字节");
        return raw;
    }

    /** 攒满一块就加密写出；关闭时补最后一块 + 终止标记 0。 */
    private static final class ChunkedCipherOutputStream extends OutputStream {
        private final OutputStream out;
        private final SecretKey key;
        private final byte[] buffer = new byte[CHUNK_BYTES];
        private int filled;
        private boolean closed;

        private ChunkedCipherOutputStream(OutputStream out, SecretKey key) {
            this.out = out;
            this.key = key;
        }

        @Override
        public void write(int value) throws IOException {
            if (filled == CHUNK_BYTES) flushChunk();
            buffer[filled++] = (byte) value;
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            int remaining = length;
            int position = offset;
            while (remaining > 0) {
                int take = Math.min(remaining, CHUNK_BYTES - filled);
                System.arraycopy(source, position, buffer, filled, take);
                filled += take;
                position += take;
                remaining -= take;
                if (filled == CHUNK_BYTES) flushChunk();
            }
        }

        @Override
        public void flush() throws IOException {
            flushChunk();
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            flushChunk();
            out.write(ByteBuffer.allocate(4).putInt(0).array());
            out.flush();
            out.close();
        }

        private void flushChunk() throws IOException {
            if (filled == 0) return;
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                byte[] cipherText = cipher.doFinal(buffer, 0, filled);
                out.write(ByteBuffer.allocate(4).putInt(cipherText.length).array());
                out.write(iv);
                out.write(cipherText);
            } catch (GeneralSecurityException error) {
                throw new IOException("备份块加密失败", error);
            } finally {
                filled = 0;
            }
        }
    }
}
