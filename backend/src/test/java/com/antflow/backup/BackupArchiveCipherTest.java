package com.antflow.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 归档格式的往返一致性：写 v2 → 读 v2；并且**旧 v1 归档仍能读**（线上已有历史备份）。
 *
 * <p>为什么要单测而不只靠端到端：格式是"三段实现"（服务、Java 工具、Node 工具），
 * 端到端只能覆盖服务 + 其中一条工具；这里把格式本身钉住，工具侧用真实归档另验。
 */
class BackupArchiveCipherTest {
    private static final char[] SECRET = "0123456789abcdef0123456789abcdef".toCharArray();

    @Test
    void chunkedRoundTripKeepsEveryByte() throws Exception {
        // 跨块边界：9MB + 37B → 3 块（4MB/4MB/1MB+37B）
        byte[] plain = new byte[9 * 1024 * 1024 + 37];
        new Random(42).nextBytes(plain);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (OutputStream out = BackupArchiveCipher.encrypt(archive, SECRET)) {
            out.write(plain);
        }
        byte[] encrypted = archive.toByteArray();

        assertThat(Arrays.copyOf(encrypted, 5)).isEqualTo(BackupArchiveCipher.MAGIC_V2);
        // 每块多 4B 长度 + 12B IV + 16B tag；末尾还有 4B 终止标记 → 密文必然比明文大
        assertThat(encrypted.length).isGreaterThan(plain.length);

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        BackupArchiveCipher.decrypt(new ByteArrayInputStream(encrypted), decrypted, SECRET);
        assertThat(decrypted.toByteArray()).isEqualTo(plain);
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (OutputStream out = BackupArchiveCipher.encrypt(archive, SECRET)) {
            out.write("secret payload".getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertThatThrownBy(() -> BackupArchiveCipher.decrypt(
            new ByteArrayInputStream(archive.toByteArray()), sink,
            "ffffffffffffffffffffffffffffffff".toCharArray()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("认证");
    }

    @Test
    void legacyV1ArchiveStillDecrypts() throws Exception {
        byte[] plain = "legacy backup body".getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        new Random(7).nextBytes(salt);
        new Random(9).nextBytes(iv);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec(SECRET, salt, 210_000, 256)).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(BackupArchiveCipher.MAGIC_V1);
        archive.write(ByteBuffer.allocate(4).putInt(salt.length).array());
        archive.write(salt);
        archive.write(iv);
        archive.write(cipher.doFinal(plain));

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        BackupArchiveCipher.decrypt(new ByteArrayInputStream(archive.toByteArray()), decrypted, SECRET);
        assertThat(decrypted.toByteArray()).isEqualTo(plain);
    }
}
