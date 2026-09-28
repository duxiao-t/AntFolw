package com.antflow.authz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 端点鉴权判据，供 {@code @PreAuthorize("@authz.console('xxx')")} 使用。
 *
 * <p>两档语义：
 * <ul>
 *   <li>{@code console(code)}：管理端专属端点，要求"入口能力 + 业务能力"同时具备；</li>
 *   <li>{@code capability(code)}：共享/移动端端点，只要求业务能力（移动端没有入口能力）。</li>
 * </ul>
 * 用组合判据而不是在注解里拼字符串，避免类级注解被方法级覆盖，也避免逐个端点重复写入口能力。
 */
@Component("authz")
@RequiredArgsConstructor
public class AuthzPolicy {
    private final AuthorizationService authorizationService;

    public boolean console(String permissionCode) {
        return authorizationService.hasPermission(PermissionCodes.CONSOLE_ACCESS)
            && authorizationService.hasPermission(permissionCode);
    }

    public boolean capability(String permissionCode) {
        return authorizationService.hasPermission(permissionCode);
    }

    /** 只要求"能进管理端"，用于自助类但仅限桌面端的端点（工作台、我的提交等）。 */
    public boolean consoleEntry() {
        return authorizationService.hasPermission(PermissionCodes.CONSOLE_ACCESS);
    }

    /** 多个能力任一即可（用于"表单管理员或流程管理员"都能进入的配置入口）。 */
    public boolean consoleAny(String... permissionCodes) {
        if (!authorizationService.hasPermission(PermissionCodes.CONSOLE_ACCESS)) {
            return false;
        }
        for (String code : permissionCodes) {
            if (authorizationService.hasPermission(code)) {
                return true;
            }
        }
        return false;
    }
}
