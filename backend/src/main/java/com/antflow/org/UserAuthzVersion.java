package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 权限版本号：改角色/状态/归属后把 {@code authz_version} 加一，并让缓存失效。
 *
 * <p>从 {@code UserService} 原样搬出（方法体逐字未改）。单独成类是因为三个写路径
 * （账号、状态、角色）都要它，而它只有两条语句——散在各自类里迟早有一处忘了 evict，
 * 表现是"改了权限要等缓存过期才生效"。
 */
@RequiredArgsConstructor
class UserAuthzVersion {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;

    void authorizationChanged(Long userId) {
        jdbcTemplate.update("UPDATE t_user SET authz_version = authz_version + 1 WHERE id = ?",
            userId);
        authorizationService.evict(userId);
    }
}
