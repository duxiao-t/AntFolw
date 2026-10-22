package com.antflow.authz;

import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理门面：角色 CRUD 的编排、审计与事务边界。
 *
 * <p>2026-10-22 按"变化原因"拆开（527 行 / 38 方法 → 231 行 / 11 方法）：
 * <ul>
 *   <li>{@link RoleStore}：角色行读模型 + {@code authz_version} 递增与快照失效</li>
 *   <li>{@link RoleGrantAdmin}：能力目录、授权校验（含天花板）、角色 × 能力落库</li>
 *   <li>{@link RoleQueryService}：角色/有效能力/用户绑定/部门候选的只读查询</li>
 * </ul>
 *
 * <p><b>事务边界留在本类</b>：{@code create/update/delete} 上的 {@code @Transactional} 一行都没动。
 * 协作者是普通 {@code @Component}，不参与事务声明——它们被本类的事务方法调用，天然在同一事务里；
 * 若给协作者也加 {@code @Transactional}，一旦日后有人从非事务入口直接调它们，语义就变了。
 *
 * <p>公开方法名与签名是契约（{@code SecurityAuthorizationController}/{@code org.RoleController}
 * 在编译期直接引用，嵌套 record 也被 {@code PostgresTransactionalIntegrityIntegrationTest} 使用），
 * 这里只做委托。测试夹具用的三参构造器保留原签名。
 */
@Service
public class RoleAdminService {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final RoleStore store;
    private final RoleGrantAdmin grants;
    private final RoleQueryService queries;

    /**
     * 生产装配：协作者由 Spring 注入。
     *
     * <p>为什么显式写 {@code @Autowired} 而不是 {@code @RequiredArgsConstructor}：本类同时保留一个
     * 三参构造器（{@code RoleAdminServiceTest} 与装配夹具用），两个构造器必须指明哪一个给容器用。
     * 协作者之间不回调本类，不存在循环依赖。
     */
    @Autowired
    public RoleAdminService(JdbcTemplate jdbcTemplate, AuthorizationService authorizationService,
                            AuditService auditService, RoleStore store, RoleGrantAdmin grants,
                            RoleQueryService queries) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.store = store;
        this.grants = grants;
        this.queries = queries;
    }

    /** 单测/装配夹具：从三个基础依赖建出整条链（store → 授权 → 查询）。保留拆分前的公开构造器签名。 */
    public RoleAdminService(JdbcTemplate jdbcTemplate, AuthorizationService authorizationService,
                            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        RoleStore store = new RoleStore(jdbcTemplate, authorizationService);
        this.store = store;
        this.grants = new RoleGrantAdmin(jdbcTemplate, authorizationService);
        this.queries = new RoleQueryService(jdbcTemplate, authorizationService, store);
    }

    public List<PermissionDto> permissions() { return grants.permissions(); }

    public List<RoleDto> roles() { return queries.roles(); }

    public RoleDto role(long id) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_READ);
        RoleDto result = store.roleOrNull(id);
        if (result == null) {
            throw new BizException("ROLE_NOT_FOUND", "role not found");
        }
        return result;
    }

    @Transactional
    public RoleDto create(RoleWriteRequest request) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
        grants.validateRequest(request, false);
        jdbcTemplate.update("""
            INSERT INTO t_role(code, name, description, enabled, builtin, version)
            VALUES (?, ?, ?, ?, false, 0)
            """, request.code().trim(), request.name().trim(), normalized(request.description()),
            request.enabled());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM t_role WHERE code = ?",
            Long.class, request.code().trim());
        grants.replaceRoleConfiguration(id, request.permissions());
        RoleDto created = store.requiredRole(id);
        auditService.success("security.role.create", "ROLE", id,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("code", "name", "enabled", "permissions")),
            java.util.Map.of("permissionCount", request.permissions().size()));
        return created;
    }

    @Transactional
    public RoleDto update(long id, RoleWriteRequest request) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
        RoleDto existing = store.roleOrNull(id);
        if (existing == null) {
            throw new BizException("ROLE_NOT_FOUND", "role not found");
        }
        grants.validateRequest(request, true);
        if (!Objects.equals(existing.version(), request.version())) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (existing.builtin()) {
            if (!existing.code().equals(request.code())
                || existing.enabled() != request.enabled()
                || !existing.permissions().stream()
                    .map(grant -> RoleGrantAdmin.grantSignature(grant.code(), grant.scopeOverride(),
                        grant.departmentIds()))
                    .collect(java.util.stream.Collectors.toSet())
                    .equals(request.permissions().stream()
                        .map(grant -> RoleGrantAdmin.grantSignature(grant.code(), grant.scopeOverride(),
                            grant.departmentIds()))
                        .collect(java.util.stream.Collectors.toSet()))) {
                throw new BizException("BUILTIN_ROLE_PROTECTED", "built-in role policy is immutable");
            }
        } else {
            if (!existing.code().equals(request.code())) {
                throw new BizException("ROLE_CODE_IMMUTABLE", "role code cannot be changed");
            }
        }
        List<Long> affectedUsers = store.usersWithRole(id);
        int updated = jdbcTemplate.update("""
            UPDATE t_role
            SET name = ?, description = ?, enabled = ?,
                version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, request.name().trim(), normalized(request.description()),
            request.enabled(), id, request.version());
        if (updated != 1) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (!existing.builtin()) {
            grants.replaceRoleConfiguration(id, request.permissions());
        }
        store.bumpUsers(affectedUsers);
        RoleDto result = store.requiredRole(id);
        auditService.success("security.role.update", "ROLE", id,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("name", "description", "enabled",
                "permissions")),
            java.util.Map.of("affectedUserCount", affectedUsers.size(),
                "permissionCount", request.permissions().size()));
        return result;
    }

    @Transactional
    public void delete(long id, int version) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
        RoleDto existing = store.roleOrNull(id);
        if (existing == null) {
            return;
        }
        if (existing.builtin() || "admin".equals(existing.code())) {
            throw new BizException("BUILTIN_ROLE_PROTECTED", "built-in roles cannot be deleted");
        }
        if (!Objects.equals(existing.version(), version)) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (existing.userCount() > 0) {
            throw new BizException("ROLE_IN_USE", "remove users from the role before deleting it");
        }
        jdbcTemplate.update("DELETE FROM t_role WHERE id = ? AND version = ?", id, version);
        auditService.success("security.role.delete", "ROLE", id,
            AuditService.RiskLevel.CRITICAL, java.util.Map.of(),
            java.util.Map.of("code", existing.code()));
    }

    public EffectivePermissionDto effective(long userId) { return queries.effective(userId); }

    public UserRolePage userAssignments(int page, int size, String keyword) {
        return queries.userAssignments(page, size, keyword);
    }

    public List<DepartmentCandidate> departmentCandidates() { return queries.departmentCandidates(); }

    /** 见 {@link RoleStore#bumpUsers}：先递增 {@code authz_version}，再 evict 本机快照。 */
    public void bumpUsers(List<Long> userIds) { store.bumpUsers(userIds); }

    /** 见 {@link RoleGrantAdmin#validateGrantCeiling}：防御纵深，不要因为"当前不可达"删掉。 */
    void validateGrantCeiling(PermissionGrantWriteRequest grant) { grants.validateGrantCeiling(grant); }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PermissionDto(String code, String name, String domain, String domainLabel,
                                String riskLevel, int sortOrder, boolean adminOnly,
                                boolean scopeable, String defaultScope) { }
    /** 一条授权：能力 + 数据范围覆盖值（null=用能力默认）+ 自定义部门明细。 */
    public record PermissionGrantDto(String code, String scopeOverride, List<Long> departmentIds) { }
    public record RoleDto(long id, String code, String name, String description,
                          boolean enabled, boolean builtin, int version,
                          List<PermissionGrantDto> permissions, long userCount) { }
    public record RoleWriteRequest(String code, String name, String description, boolean enabled,
                                   Integer version,
                                   Set<PermissionGrantWriteRequest> permissions) {
        public RoleWriteRequest {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
    public record PermissionGrantWriteRequest(String code, String scopeOverride,
                                              Set<Long> departmentIds) {
        public PermissionGrantWriteRequest {
            departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
        }
    }
    public record EffectivePermissionDto(long userId, Set<String> roleCodes,
                                          Set<String> permissions, Long departmentId,
                                          boolean admin,
                                          Map<String, PermissionScopeDto> permissionScopes) { }
    public record PermissionScopeDto(Set<DataScope> modes, Set<Long> departmentIds,
                                     boolean all) { }
    public record DepartmentCandidate(long id, String name) { }
    public record UserRoleView(long id, String username, String displayName, String employeeNo,
                               String status, Long departmentId, List<Long> roleIds,
                               long authzVersion) { }
    public record UserRolePage(List<UserRoleView> records, long total, int page, int size) { }
}
