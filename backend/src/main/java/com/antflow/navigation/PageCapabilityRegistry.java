package com.antflow.navigation;

import com.antflow.authz.PermissionCatalog;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 服务端可信的页面能力清单；前后端构建读取仓库中的同一份 JSON。 */
@Component
public class PageCapabilityRegistry {
    private final Map<String, List<String>> capabilities;

    public PageCapabilityRegistry(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(
                "antflow/page-capabilities.json").getInputStream()) {
            Map<String, List<String>> loaded = objectMapper.readValue(input,
                new TypeReference<LinkedHashMap<String, List<String>>>() { });
            loaded.forEach((pageKey, permissions) -> {
                if (pageKey == null || pageKey.isBlank() || permissions == null) {
                    throw new IllegalStateException("invalid page capability entry");
                }
                permissions.forEach(code -> {
                    if (!PermissionCatalog.isKnown(code)) {
                        throw new IllegalStateException(
                            "unknown page capability: " + pageKey + " -> " + code);
                    }
                });
            });
            Map<String, List<String>> immutable = new LinkedHashMap<>();
            loaded.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            capabilities = java.util.Collections.unmodifiableMap(immutable);
        } catch (Exception error) {
            throw new IllegalStateException("cannot load page capability registry", error);
        }
    }

    public List<String> require(String pageKey) {
        List<String> required = capabilities.get(pageKey);
        if (required == null) {
            throw new BizException("MENU_PAGE_KEY_UNKNOWN",
                "当前版本不存在该页面: " + pageKey);
        }
        return required;
    }

    public Set<String> pageKeys() {
        return capabilities.keySet();
    }
}
