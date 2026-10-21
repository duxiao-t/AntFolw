package com.antflow.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 归档的机密性与完整性：AFBK2 写出的字节必须能读回原文，且**任何一个字节被改动都要失败**。
 *
 * <p>这个类原来用反射调 `BackupService#encrypt`（加密逻辑曾内联在服务里）；格式抽到
 * {@link BackupArchiveCipher} 之后直接测它——反射测私有方法本就是权宜之计。
 */
class BackupCryptoTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void encryptsAndRejectsTamperedBackupBytes() throws Exception {
        byte[] plain = "backup-body-with-enough-bytes-to-fill-one-chunk".getBytes(
            StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (OutputStream out = BackupArchiveCipher.encrypt(archive, SECRET.toCharArray())) {
            out.write(plain);
        }
        byte[] encrypted = archive.toByteArray();
        assertThat(Arrays.copyOf(encrypted, 5)).isEqualTo(BackupArchiveCipher.MAGIC_V2);

        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        BackupArchiveCipher.decrypt(new ByteArrayInputStream(encrypted), decrypted,
            SECRET.toCharArray());
        assertThat(decrypted.toByteArray()).isEqualTo(plain);

        // 篡改最后一个字节附近（落在末块的密文/标签区，终止标记在最后 4 字节）
        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 20] ^= 0x01;
        assertThatThrownBy(() -> BackupArchiveCipher.decrypt(new ByteArrayInputStream(tampered),
            new ByteArrayOutputStream(), SECRET.toCharArray()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("认证");
    }
}
