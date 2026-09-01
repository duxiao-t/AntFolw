package com.antflow.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.auth")
public class ExternalAuthProperties {
    private String publicBaseUrl = "http://localhost:5173";
    private List<String> oidcAllowedHosts = new ArrayList<>();
    private boolean oidcHttpsOnly = true;

    public URI publicBaseUri() {
        URI uri = URI.create(publicBaseUrl.endsWith("/")
            ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null) {
            throw new IllegalStateException("ANTFLOW_PUBLIC_BASE_URL must be an absolute origin");
        }
        return uri;
    }
}
