package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService.AuthzSnapshot;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 能力码判定：当前请求主体（或指定用户）是否持有某个原子能力。
 *
 * <p>从 {@link AuthorizationService} 搬出（2026-10-22 第二刀）。这一簇改动的触发因素只有
 * "能力码语义"，与数据范围/资源可见性无关——它们判定的是"能不能进这个功能"，不是"能看到哪些行"。
 *
 * <p><b>fail-closed 的两处细节，改之前先看这里：</b>
 * <ol>
 *   <li>没有请求主体时 {@link #principal()} 直接抛 {@code AccessDeniedException}。
 *       {@code hasPermission(String)} 是唯一的"软"入口——它返回 false 而不是抛异常，
 *       供 {@code @PreAuthorize} 这类判定使用；两者不要互相替代。</li>
 *   <li>管理员旁路只认 principal 上的 {@code admin} 角色（{@code principal.isAdmin()}），
 *       不认"委派出来的权限"——{@code requireAdmin()} 因此不接受只有管理类能力的账号。</li>
 * </ol>
 */
@Component
public class CapabilityResolver {
    private final AuthzSnapshotStore snapshots;

    CapabilityResolver(AuthzSnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    public void requirePermission(String permission) {
        PrincipalHolder.Principal principal = principal();
        if (!principal.isAdmin() && !principal.permissions().contains(permission)) {
            throw new AuthorizationFailureException("MISSING_PERMISSION",
                "missing permission: " + permission);
        }
    }

    /** 多个能力任一即可（例如"表单管理员或流程管理员"都能配置流程）。 */
    public void requireAnyPermission(String... permissions) {
        PrincipalHolder.Principal principal = principal();
        if (principal.isAdmin()) {
            return;
        }
        for (String permission : permissions) {
            if (principal.permissions().contains(permission)) {
                return;
            }
        }
        throw new AuthorizationFailureException("MISSING_PERMISSION",
            "missing permission: " + String.join(" or ", permissions));
    }

    public void requireAdmin() {
        if (!principal().isAdmin()) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    public boolean hasPermission(String permission) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElse(null);
        return principal != null
            && (principal.isAdmin() || principal.permissions().contains(permission));
    }

    /**
     * 指定用户（而不是当前请求主体）是否持有该能力。
     * 服务层按 userId 判定时必须用这个——与 canUseForm/hasFormGrant 一样以入参用户为准，
     * 否则"按 A 用户查数据"和"用当前主体的能力判定"会错配。
     */
    public boolean hasPermission(long userId, String permission) {
        AuthzSnapshot snapshot = snapshots.snapshot(userId);
        return snapshot.admin() || snapshot.permissions().contains(permission);
    }

    public boolean isAdmin() {
        return principal().isAdmin();
    }

    public long currentUserId() {
        return principal().userId();
    }

    public AuthzSnapshot currentSnapshot() {
        PrincipalHolder.Principal principal = principal();
        return snapshots.snapshot(principal.userId());
    }

    /** 包内可见：数据范围/资源可见性判定都要"当前主体"，但都不该各自实现"未登录就拒绝"。 */
    PrincipalHolder.Principal principal() {
        return PrincipalHolder.current().orElseThrow(() ->
            new AccessDeniedException("authentication required"));
    }
}
