package com.antflow.auth;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BootstrapCredentialInitializer implements ApplicationRunner, InitializingBean {
    static final String MARKER = "!ANTFLOW_BOOTSTRAP_REQUIRED!";
    /** 仓库里出现过、已经公开的共享口令；拿它们当初始化口令等于没整改。 */
    private static final java.util.Set<String> KNOWN_SHARED_PASSWORDS =
        java.util.Set.of("ant.design", "qwer1234");
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
        // 库里有等待初始化的哨兵账号却没配口令：无论什么 profile 都拒绝启动。
        // 默认 profile 下启动会跳过初始化，应用看着正常但没人能登录，而 Flyway 不会回滚那步迁移。
        if (adminPassword == null && markedAccountExists()) {
            throw new IllegalStateException(
                "ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD must be configured while a seed account is awaiting initialization");
        }
        validate(adminPassword, "ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD");
        validate(bobPassword, "ANTFLOW_BOOTSTRAP_BOB_PASSWORD");
    }

    private boolean markedAccountExists() {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM t_user WHERE password_hash = ?", Long.class, MARKER);
        return count != null && count > 0;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize("admin", adminPassword);
        initialize("bob", bobPassword);
        warnOnRemainingMarkers();
    }

    /**
     * 初始化后仍有哨兵账号：应用能正常启动，但相关账号既登不进去也改不了密码。
     * 典型场景是种子账号 bob 没配 {@code ANTFLOW_BOOTSTRAP_BOB_PASSWORD}——
     * 只靠"登不上"很难定位，这里显式打印出来。
     *
     * <p>刻意选择 WARN 而不是抛异常：存量部署里 bob 可能本来就没人用，直接拒绝启动会把
     * "某个账号不可用"升级成"整个服务不可用"，故障面反而扩大。真正致命的场景
     * （有哨兵账号却没配 admin 口令）在 {@link #afterPropertiesSet()} 里已经 fail-closed。
     */
    private void warnOnRemainingMarkers() {
        List<String> remaining = jdbc.queryForList(
            "SELECT username FROM t_user WHERE password_hash = ? ORDER BY username",
            String.class, MARKER);
        if (!remaining.isEmpty()) {
            log.warn("仍有 {} 个账号停留在待初始化状态（哨兵口令），无法登录：{}。"
                    + "请在环境变量中配置 ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD / ANTFLOW_BOOTSTRAP_BOB_PASSWORD 后重启，"
                    + "或由管理员在「系统管理 → 用户管理」中重置这些账号的密码。",
                remaining.size(), String.join(", ", remaining));
        }
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
        if (value == null) return;
        if (KNOWN_SHARED_PASSWORDS.contains(value.toLowerCase(java.util.Locale.ROOT))) {
            // V48 刚把这两个口令从库里清掉，再用它们初始化等于整改白做。
            throw new IllegalStateException(name + " must not be a known shared password");
        }
        // BCrypt 只认前 72 字节，多出来的部分会被静默截断——所以按 UTF-8 字节数卡，
        // 不能按字符数（19 个汉字之后再追加不同后缀会匹配到同一个哈希）。
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) {
            throw new IllegalStateException(name + " must be 8 to 72 bytes");
        }
    }
}
