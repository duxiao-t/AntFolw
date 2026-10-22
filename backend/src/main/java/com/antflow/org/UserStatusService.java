package com.antflow.org;

import com.antflow.auth.AuthSessionService;
import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户状态与凭据：启用/停用、企业微信登录权限、管理员重置密码、自助改密。
 *
 * <p>从 {@code UserService} 原样搬出（方法体逐字未改）。**密码语义一条都不能动**：
 * <ul>
 *   <li>写入前一律 {@code encoder.encode}，库里只存哈希；</li>
 *   <li>校验原密码走 {@code encoder.matches}，原密码为空直接判错（别让 null 走到 matches）；</li>
 *   <li>长度策略只有 {@link UserGuards#validatePassword} 一处（8~64），所有入口共用。</li>
 * </ul>
 *
 * <p>事务注解留在门面（{@code @Transactional} 只有走代理才生效，加在这里是静默失效）。
 */
@RequiredArgsConstructor
class UserStatusService {
    private final UserMapper userMapper;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbcTemplate;
    private final AuthSessionService authSessionService;
    private final AuthorizationService authorizationService;
    private final UserRoles roles;
    private final UserGuards guards;
    private final UserAuthzVersion authzVersion;
    private final UserQueryService queries;

    void changeStatus(User user, String status) {
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new BizException("BAD_USER_STATUS", "用户状态无效");
        }
        if ("ACTIVE".equals(user.getStatus()) && "DISABLED".equals(status)
            && roles.rolesOf(user.getId()).contains("admin") && roles.activeAdminCount() <= 1) {
            throw new BizException("LAST_ADMIN_PROTECTED", "至少保留一个启用的管理员");
        }
        if ("ACTIVE".equals(user.getStatus()) && "DISABLED".equals(status)
            && lastActiveFormMaintainer(user.getId())) {
            throw new BizException("LAST_FORM_MAINTAINER_PROTECTED",
                "该用户是部分表单的最后一名有效维护人，请先转交维护职责");
        }
        user.setStatus(status);
        if (!"ACTIVE".equals(status)) {
            authSessionService.revokeAll(user.getId());
        }
    }

    private boolean lastActiveFormMaintainer(Long userId) {
        Boolean result = jdbcTemplate.queryForObject("""
            SELECT EXISTS (
              SELECT 1
              FROM t_form_maintainer own_maintenance
              JOIN t_form_definition form
                ON form.id = own_maintenance.form_def_id AND form.deleted = 0
              WHERE own_maintenance.user_id = ?
                AND NOT EXISTS (
                  SELECT 1
                  FROM t_form_maintainer other_maintenance
                  JOIN t_user other_user ON other_user.id = other_maintenance.user_id
                  WHERE other_maintenance.form_def_id = own_maintenance.form_def_id
                    AND other_maintenance.user_id <> ?
                    AND other_user.status = 'ACTIVE'
                )
            )
            """, Boolean.class, userId, userId);
        return Boolean.TRUE.equals(result);
    }

    void resetPassword(Long userId, String rawPassword) {
        authorizationService.requirePermission(
            com.antflow.authz.PermissionCodes.ORG_USER_CREDENTIALS_MANAGE);
        // 重置管理员的密码＝直接接管管理员账号，委派的凭据管理员不能碰。
        if (roles.rolesOf(userId).contains("admin")) {
            authorizationService.requireAdmin();
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        guards.validatePassword(rawPassword);
        user.setPasswordHash(encoder.encode(rawPassword));
        userMapper.updateById(user);
        authSessionService.revokeAll(userId);
    }

    /**
     * 自助修改密码：校验原密码 → 复用同一条长度策略 → 下线除当前会话之外的其它设备。
     * 管理员重置他人密码走 {@link #resetPassword}，那条路会踢掉全部会话。
     */
    void changeOwnPassword(long userId, UUID currentSessionId,
                           String currentPassword, String nextPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BizException("PASSWORD_INCORRECT", "原密码不正确");
        }
        guards.validatePassword(nextPassword);
        user.setPasswordHash(encoder.encode(nextPassword));
        userMapper.updateById(user);
        authSessionService.revokeAllExcept(userId, currentSessionId);
    }

    /**
     * 企业微信成员的登录开关。只有"目录里在、且企微状态允许"（1 已激活 / 4 未激活）的成员能动；
     * 改完同步本地状态（{@link #changeStatus} 里还有管理员/表单维护人的兜底保护）。
     */
    User setWecomLoginAccess(Long userId, boolean enabled) {
        authorizationService.requirePermission(
            com.antflow.authz.PermissionCodes.ORG_USER_CREDENTIALS_MANAGE);
        // 改管理员的登录方式等于管理员的账号安全，委派的凭据管理员不能碰。
        if (roles.rolesOf(userId).contains("admin")) {
            authorizationService.requireAdmin();
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        if (!enabled && authorizationService.currentUserId() == userId) {
            throw new BizException("SELF_USER_PROTECTED", "不能禁止当前登录账号");
        }
        WecomLoginState mapping = jdbcTemplate.query("""
            SELECT mapping.company_id, mapping.wecom_status, mapping.directory_present
            FROM t_wecom_user_mapping mapping
            JOIN t_user user_row ON user_row.id = mapping.user_id
            JOIN t_department department ON department.id = user_row.dept_id
              AND department.company_id = mapping.company_id
            WHERE mapping.user_id = ?
            FOR UPDATE OF mapping
            """, resultSet -> resultSet.next()
                ? new WecomLoginState(resultSet.getLong("company_id"),
                    (Integer) resultSet.getObject("wecom_status"),
                    resultSet.getBoolean("directory_present"))
                : null, userId);
        if (mapping == null) {
            throw new BizException("WECOM_ACCOUNT_REQUIRED", "该成员不是企业微信同步账号");
        }
        if (!mapping.directoryPresent() || mapping.wecomStatus() == null
            || (mapping.wecomStatus() != 1 && mapping.wecomStatus() != 4)) {
            throw new BizException("WECOM_LOGIN_ACCESS_LOCKED", "当前企业微信状态不允许修改登录权限");
        }
        if (!enabled && roles.rolesOf(userId).contains("admin")) {
            roles.lockAdminRole();
            user = userMapper.selectById(userId);
            if (user == null) throw new BizException("NOT_FOUND", "用户不存在");
        }
        jdbcTemplate.update("""
            UPDATE t_wecom_user_mapping
            SET login_enabled_override = ?
            WHERE company_id = ? AND user_id = ?
            """, enabled, mapping.companyId(), userId);
        changeStatus(user, enabled ? "ACTIVE" : "DISABLED");
        userMapper.updateById(user);
        authzVersion.authorizationChanged(userId);
        queries.fillDirectoryMetadata(List.of(user));
        return user;
    }

    boolean hasWecomMapping(Long userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM t_wecom_user_mapping mapping
                JOIN t_user user_row ON user_row.id = mapping.user_id
                JOIN t_department department ON department.id = user_row.dept_id
                  AND department.company_id = mapping.company_id
                WHERE mapping.user_id = ?
            )
            """, Boolean.class, userId));
    }

    record WecomLoginState(long companyId, Integer wecomStatus,
                           boolean directoryPresent) { }
}
