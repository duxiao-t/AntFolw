package com.antflow.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class AuditServiceTest {
    @Test
    void executePropagatesAuditFailureAfterBusinessOperation() {
        AuditService service = new AuditService(Mockito.mock(JdbcTemplate.class),
            new ObjectMapper(), new TrustedProxyProperties());
        java.util.concurrent.atomic.AtomicBoolean operated =
            new java.util.concurrent.atomic.AtomicBoolean();

        assertThatThrownBy(() -> service.execute(
            () -> {
                operated.set(true);
                return 41L;
            },
            result -> {
                throw new IllegalStateException("audit unavailable");
            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("audit unavailable");
        assertThat(operated).isTrue();
    }

    @Test
    void forwardedAddressIsUsedOnlyForTrustedProxy() {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxies(java.util.List.of("10.0.0.0/8"));
        AuditService service = new AuditService(Mockito.mock(JdbcTemplate.class),
            new ObjectMapper(), properties);

        // 多跳时取**最右**值：它是我们自己的代理追加的、代理实际看到的对端地址。
        // （旧断言取的是最左的 203.0.113.9，那正是客户端自带、可伪造的值——
        //   用最左会让登录限流按伪造 IP 分桶、审计 client_ip 同时失真。）
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.2.3.4");
        trusted.addHeader("X-Forwarded-For", "203.0.113.9, 10.2.3.4");
        assertThat(service.clientIp(trusted)).isEqualTo("10.2.3.4");

        // nginx 改成覆写后只有一个值：取最右 == 唯一值。
        MockHttpServletRequest single = new MockHttpServletRequest();
        single.setRemoteAddr("10.2.3.4");
        single.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(service.clientIp(single)).isEqualTo("203.0.113.9");

        // 非可信来源直接用自己的 remoteAddr，头一律不信。
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("192.0.2.10");
        untrusted.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(service.clientIp(untrusted)).isEqualTo("192.0.2.10");
    }

    @Test
    void explicitActorSnapshotAndSanitizedDiffArePersisted() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        AuditService service = new AuditService(jdbcTemplate, new ObjectMapper(),
            new TrustedProxyProperties());
        UUID sessionId = UUID.randomUUID();
        var actor = new com.antflow.auth.PrincipalHolder.Principal(
            7L, "admin", "AntFlow Admin", Set.of("admin"), Set.of("security.audit.read"),
            3L, 10L, sessionId);

        service.successAs(actor, "auth.login", "USER", 7L,
            AuditService.RiskLevel.NORMAL,
            Map.of("password", "never-store", "changedFields", java.util.List.of("status")),
            Map.of("token", "never-store", "username", "admin"));

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), values.capture());
        Object[] args = values.getValue();
        assertThat(args[2]).isEqualTo(7L);
        assertThat(args[3]).isEqualTo("admin");
        assertThat(args[4]).isEqualTo("AntFlow Admin");
        assertThat(args[5]).isEqualTo(sessionId);
        assertThat(args[14].toString()).contains("changedFields").doesNotContain("never-store");
        assertThat(args[15].toString()).contains("username").doesNotContain("never-store");
    }
}
