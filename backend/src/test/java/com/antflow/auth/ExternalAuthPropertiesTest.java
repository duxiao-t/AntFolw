package com.antflow.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ANTFLOW_PUBLIC_BASE_URL} 的启动校验。
 *
 * <p>这个值会被拼进 OIDC 回跳、企业微信免登回调与「移动端应用主页」。
 * 以前它只在真正发链接时才解析，配错了应用照样启动，直到用户点登录才暴露。
 */
class ExternalAuthPropertiesTest {

    @Test
    void acceptsAbsoluteHttpOrigin() {
        ExternalAuthProperties props = new ExternalAuthProperties();
        props.setPublicBaseUrl("https://antflow.example.com/");
        assertThat(props.publicBaseUri().toString()).isEqualTo("https://antflow.example.com");
        assertThatCode(props::validateOnStartup).doesNotThrowAnyException();
    }

    /** 指向本机合法但几乎肯定是漏配：只 WARN，不阻断（官方 docker 本地启动就靠它）。 */
    @Test
    void loopbackHostOnlyWarns() {
        ExternalAuthProperties props = new ExternalAuthProperties();
        props.setPublicBaseUrl("http://127.0.0.1:7070");
        assertThatCode(props::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonAbsoluteValue() {
        ExternalAuthProperties props = new ExternalAuthProperties();
        props.setPublicBaseUrl("localhost:7070");
        assertThatThrownBy(props::validateOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("absolute origin");
    }

    @Test
    void rejectsNonHttpScheme() {
        ExternalAuthProperties props = new ExternalAuthProperties();
        // 用有 host 的非 http scheme：file:///etc/passwd 的 host 是 null，会先被"absolute origin"拦下。
        props.setPublicBaseUrl("ftp://example.com");
        assertThatThrownBy(props::validateOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("http or https");
    }
}
