package com.antflow.auth;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapCredentialInitializer implements ApplicationRunner, InitializingBean {
    static final String MARKER = "!ANTFLOW_BOOTSTRAP_REQUIRED!";
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    private final String adminPassword;
    private final String bobPassword;

    public BootstrapCredentialInitializer(
            JdbcTemplate jdbc, PasswordEncoder passwords, Environment environment,
            @Value("${antflow.auth.bootstrap-admin-password:}") String adminPassword,
            @Value("${antflow.auth.bootstrap-bob-password:}") String bobPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.environment = environment;
        this.adminPassword = normalized(adminPassword);
        this.bobPassword = normalized(bobPassword);
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(Profiles.of("prod")) && adminPassword == null) {
            throw new IllegalStateException(
                "ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD must be configured in production");
        }
        validate(adminPassword, "ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD");
        validate(bobPassword, "ANTFLOW_BOOTSTRAP_BOB_PASSWORD");
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize("admin", adminPassword);
        initialize("bob", bobPassword);
    }

    private void initialize(String username, String rawPassword) {
        if (rawPassword == null) return;
        jdbc.update("UPDATE t_user SET password_hash = ? WHERE username = ? AND password_hash = ?",
            passwords.encode(rawPassword), username, MARKER);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void validate(String value, String name) {
        if (value != null && (value.length() < 8 || value.length() > 64)) {
            throw new IllegalStateException(name + " must be 8 to 64 characters");
        }
    }
}
