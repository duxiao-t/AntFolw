package com.antflow.auth;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "antflow.auth")
public class ExternalAuthProperties {
    private String publicBaseUrl = "http://localhost:5173";
    private List<String> oidcAllowedHosts = new ArrayList<>();
    private boolean oidcHttpsOnly = true;

    /** 指向本机的主机名集合：这类值合法但几乎肯定是漏配（换机器访问就会失效）。 */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "0.0.0.0");

    /**
     * 启动时把配置钉死：非法就**直接起不来**，别带病运行。
     *
     * <p>历史上这个值只在真正发链接时才被解析，配错了（比如写成 {@code abc}）应用照样启动，
     * 直到用户点 OIDC 登录或企微免登才暴露。
     *
     * <p>指向本机时只 WARN 不阻断：{@code SPRING_PROFILES_ACTIVE=prod} 也用于官方 docker 本地快速启动，
     * 那场景下 127.0.0.1 是正确的。
     */
    @PostConstruct
    void validateOnStartup() {
        URI uri = publicBaseUri();
        if (LOOPBACK_HOSTS.contains(uri.getHost())) {
            log.warn("ANTFLOW_PUBLIC_BASE_URL={} 指向本机：OIDC 回跳与企业微信「移动端应用主页」会生成 {} 开头的链接，"
                + "一经从其它机器访问就会失效。局域网/域名部署请把它设成实际对外入口（如 http://192.168.1.20:7070 "
                + "或 https://antflow.example.com）。", publicBaseUrl, uri.getHost());
        }
    }

    public URI publicBaseUri() {
        URI uri = URI.create(publicBaseUrl.endsWith("/")
            ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null) {
            throw new IllegalStateException("ANTFLOW_PUBLIC_BASE_URL must be an absolute origin");
        }
        // 只允许 http/https：这个值会被拼进跳转链接，别的 scheme（file:、javascript:）没有合理用途。
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException(
                "ANTFLOW_PUBLIC_BASE_URL must use http or https, got: " + scheme);
        }
        return uri;
    }
}
