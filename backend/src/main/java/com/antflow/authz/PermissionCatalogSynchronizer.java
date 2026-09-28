package com.antflow.authz;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以 {@link PermissionCatalog} 为准同步 t_permission：
 * 目录新增/变更的能力点 upsert；目录里没有的标 deprecated（不物理删，历史授权仍可读）；
 * admin 角色补齐显式授权行。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionCatalogSynchronizer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        sync();
    }

    @Transactional
    public void sync() {
        List<PermissionCatalog.Entry> entries = PermissionCatalog.entries();
        entries.forEach(this::upsert);
        int deprecated = deprecateUnknown(entries);
        int granted = grantAdmin(entries);
        if (deprecated + granted > 0) {
            log.info("permission catalog synced: {} entries, {} deprecated, {} admin grants added",
                entries.size(), deprecated, granted);
        }
    }

    private void upsert(PermissionCatalog.Entry entry) {
        jdbcTemplate.update("""
            INSERT INTO t_permission(code, name, domain, risk_level, admin_only,
                                     scopeable, default_scope, sort_order, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (code) DO UPDATE SET
                name = EXCLUDED.name,
                domain = EXCLUDED.domain,
                risk_level = EXCLUDED.risk_level,
                admin_only = EXCLUDED.admin_only,
                scopeable = EXCLUDED.scopeable,
                default_scope = EXCLUDED.default_scope,
                sort_order = EXCLUDED.sort_order,
                deprecated_at = NULL,
                updated_at = now()
            """, entry.code(), entry.name(), entry.domain(), entry.risk().name(),
            entry.adminOnly(), entry.scopeable(),
            entry.defaultScope() == null ? null : entry.defaultScope().name(),
            PermissionCatalog.sortOrder(entry.code()));
    }

    private int deprecateUnknown(List<PermissionCatalog.Entry> entries) {
        String placeholders = IntStream.range(0, entries.size())
            .mapToObj(index -> "?")
            .collect(Collectors.joining(", "));
        String sql = """
            UPDATE t_permission SET deprecated_at = now(), updated_at = now()
            WHERE deprecated_at IS NULL AND code NOT IN (%s)
            """.formatted(placeholders);
        Object[] codes = entries.stream().map(PermissionCatalog.Entry::code).toArray();
        return jdbcTemplate.update(sql, codes);
    }

    private int grantAdmin(List<PermissionCatalog.Entry> entries) {
        int granted = 0;
        for (PermissionCatalog.Entry entry : entries) {
            granted += jdbcTemplate.update("""
                INSERT INTO t_role_permission(role_id, permission_code, scope_override)
                SELECT role.id, ?, NULL FROM t_role role WHERE role.code = 'admin'
                ON CONFLICT (role_id, permission_code) DO NOTHING
                """, entry.code());
        }
        return granted;
    }
}
