package com.antflow.automation;

import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class WebhookSecurityPolicy {
    private final AutomationProperties properties;
    private final Environment environment;

    public void validate(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException("BAD_WEBHOOK", "Webhook 仅支持 HTTP 或 HTTPS");
        }
        if ((properties.isHttpsOnly() || environment.acceptsProfiles(Profiles.of("prod")))
            && !"https".equals(scheme)) {
            throw new BizException("BAD_WEBHOOK", "生产环境 Webhook 必须使用 HTTPS");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || !isAllowedHost(host)) {
            throw new BizException("BAD_WEBHOOK", "Webhook 主机不在允许列表中");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw new BizException("BAD_WEBHOOK", "Webhook 目标地址不允许访问");
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("BAD_WEBHOOK", "Webhook 主机无法解析");
        }
    }

    public void validateDefinition(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException("BAD_WEBHOOK", "Webhook 仅支持 HTTP 或 HTTPS");
        }
        if ((properties.isHttpsOnly() || environment.acceptsProfiles(Profiles.of("prod")))
            && !"https".equals(scheme)) {
            throw new BizException("BAD_WEBHOOK", "生产环境 Webhook 必须使用 HTTPS");
        }
        if (uri.getHost() == null || !isAllowedHost(uri.getHost())) {
            throw new BizException("BAD_WEBHOOK", "Webhook 主机不在允许列表中");
        }
    }

    private boolean isAllowedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return properties.getAllowedHosts().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(pattern -> pattern.startsWith("*.")
                ? normalized.endsWith(pattern.substring(1))
                    && normalized.length() > pattern.length() - 1
                : normalized.equals(pattern));
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        String normalized = address.getHostAddress().split("%")[0].toLowerCase(Locale.ROOT);
        if (Arrays.asList("169.254.169.254", "100.100.100.200", "fd00:ec2::254")
            .contains(normalized)) {
            return true;
        }
        boolean allowPrivateForDevelopment = properties.isAllowPrivateAddresses()
            && !environment.acceptsProfiles(Profiles.of("prod"));
        return !allowPrivateForDevelopment && (address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress());
    }
}
