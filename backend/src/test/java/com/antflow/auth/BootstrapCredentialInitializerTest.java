package com.antflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapCredentialInitializerTest {
    @Test
    void initializesOnlyMarkedSeedAccounts() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("strong-password")).thenReturn("encoded");
        BootstrapCredentialInitializer initializer = new BootstrapCredentialInitializer(
            jdbc, passwords, new MockEnvironment(), "strong-password", "");

        initializer.afterPropertiesSet();
        initializer.run(null);

        verify(jdbc).update(
            "UPDATE t_user SET password_hash = ? WHERE username = ? AND password_hash = ?",
            "encoded", "admin", BootstrapCredentialInitializer.MARKER);
    }

    @Test
    void productionRequiresAnExplicitAdminPassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        BootstrapCredentialInitializer initializer = new BootstrapCredentialInitializer(
            mock(JdbcTemplate.class), mock(PasswordEncoder.class), environment, "", "");

        assertThatThrownBy(initializer::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD");
    }
}
