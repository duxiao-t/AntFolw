package com.antflow.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WecomSecretCipherTest {
    @Test
    void encryptsWithRandomIvAndBindsCiphertextToCompany() {
        WecomProperties properties = new WecomProperties();
        properties.setEncryptionKey("test-key-that-is-not-the-development-default");
        WecomSecretCipher cipher = new WecomSecretCipher(properties);

        String first = cipher.encrypt("directory-secret", 7);
        String second = cipher.encrypt("directory-secret", 7);

        assertThat(first).isNotEqualTo(second).doesNotContain("directory-secret");
        assertThat(cipher.decrypt(first, 7)).isEqualTo("directory-secret");
        assertThatThrownBy(() -> cipher.decrypt(first, 8))
            .isInstanceOf(IllegalStateException.class);
    }
}
