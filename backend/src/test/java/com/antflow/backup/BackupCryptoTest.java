package com.antflow.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditService;
import com.antflow.audit.TrustedProxyProperties;
import com.antflow.mobile.workflow.MobileFileProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executor;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BackupCryptoTest {
    @Test
    void encryptsAndRejectsTamperedBackupBytes() throws Exception {
        String secret = "0123456789abcdef0123456789abcdef";
        BackupProperties properties = new BackupProperties();
        properties.setEncryptionSecret(secret);
        BackupService service = new BackupService(mock(JdbcTemplate.class),
            mock(AuthorizationService.class), mock(AuditService.class), properties,
            new MobileFileProperties(), new TrustedProxyProperties(), new ObjectMapper(),
            Runnable::run);
        Method encrypt = BackupService.class.getDeclaredMethod("encrypt", java.io.OutputStream.class);
        encrypt.setAccessible(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var encrypted = (java.io.OutputStream) encrypt.invoke(service, output)) {
            encrypted.write("backup-content".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = output.toByteArray();

        assertThat(decrypt(bytes, secret)).isEqualTo("backup-content");
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> decrypt(bytes, secret)).isInstanceOf(Exception.class);
    }

    private static String decrypt(byte[] bytes, String secret) throws Exception {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            assertThat(input.readNBytes(5)).isEqualTo("AFBK1".getBytes(StandardCharsets.US_ASCII));
            int saltLength = ByteBuffer.wrap(input.readNBytes(4)).getInt();
            byte[] salt = input.readNBytes(saltLength);
            byte[] iv = input.readNBytes(12);
            PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, 210_000, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
            try (CipherInputStream decrypted = new CipherInputStream(input, cipher)) {
                return new String(decrypted.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
