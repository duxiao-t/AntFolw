package com.antflow.authz;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表单资源授权门面：使用授权（{@code t_form_resource_grant}）与维护人（{@code t_form_maintainer}）。
 *
 * <p>2026-10-22 按"变化原因"拆开（508 行 / 36 方法 → 229 行 / 8 方法）：
 * <ul>
 *   <li>{@link FormGrantStore}：使用授权真源的读写 + 表单级 {@code authz_version} 锁/递增</li>
 *   <li>{@link FormMaintainerStore}：维护人真源的读写</li>
 *   <li>{@link FormGrantCandidateQuery}：候选用户/角色/部门/公司查询 + 写入主体校验</li>
 * </ul>
 *
 * <p><b>两条不能动的语义：</b>
 * <ol>
 *   <li>两张表是**两套真源**，互不蕴含，也不合并：{@code FormAccessGuard} 读它们时走两条独立路径，
 *       这里必须保持同样的读写口径（只有使用授权的人不能改表单配置）。</li>
 *   <li>事务边界留在这里：{@code replace}/{@code replaceMaintainers}/{@code grantCreator} 上的
 *       {@code @Transactional} 一行都没动，协作者是普通 {@code @Component}，不再声明事务。</li>
 * </ol>
 *
 * <p>{@link #grantCreator} 是唯一同时写两张表的方法，故意留在门面：它是"新建表单时把创建者同时
 * 设成使用授权与维护人"的引导动作，拆到任何一个 Store 都会让那个 Store 越界写另一张表。
 *
 * <p>公开方法名与签名、嵌套 record 都是契约（{@code FormGrantController}、
 * {@code FormDefinitionService}、{@code PostgresTransactionalIntegrityIntegrationTest} 直接引用）。
 */
@Service
public class FormGrantService {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final FormGrantStore store;
    private final FormMaintainerStore maintainers;
    private final FormGrantCandidateQuery candidates;

    /**
     * 生产装配：协作者由 Spring 注入。
     *
     * <p>为什么显式写 {@code @Autowired} 而不是 {@code @RequiredArgsConstructor}：本类同时保留一个
     * 三参构造器（装配夹具与既有调用方使用），两个构造器必须指明哪一个给容器用。
     */
    @Autowired
    public FormGrantService(JdbcTemplate jdbcTemplate, AuthorizationService authorizationService,
                            AuditService auditService, FormGrantStore store,
                            FormMaintainerStore maintainers, FormGrantCandidateQuery candidates) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.store = store;
        this.maintainers = maintainers;
        this.candidates = candidates;
    }

    /** 单测/装配夹具：从三个基础依赖建出整条链（授权存储 → 维护人 → 候选查询）。保留拆分前的公开构造器签名。 */
    public FormGrantService(JdbcTemplate jdbcTemplate, AuthorizationService authorizationService,
                            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        FormGrantStore store = new FormGrantStore(jdbcTemplate, authorizationService);
        this.store = store;
        this.maintainers = new FormMaintainerStore(jdbcTemplate, authorizationService, store);
        this.candidates = new FormGrantCandidateQuery(jdbcTemplate, authorizationService);
    }

    public FormGrantDto get(long formId) { return store.get(formId); }

    public FormGrantCandidates candidates(long formId) { return candidates.candidates(formId); }

    public FormGrantCandidates candidates() { return candidates.candidates(); }

    public GrantUserPage userCandidates(Long formId, int page, int size, String keyword,
                                        Long departmentId) {
        return candidates.userCandidates(formId, page, size, keyword, departmentId);
    }

    @Transactional
    public FormGrantDto replace(long formId, FormGrantWriteRequest request) {
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        if (request == null || request.version() == null) {
            throw new BizException("FORM_GRANT_VERSION_REQUIRED", "grant version is required");
        }
        candidates.validateSubjects(request.userIds(), request.roleIds(), request.departmentIds());
        boolean admin = authorizationService.isAdmin();
        if (request.companyIds() != null) {
            if (!admin) {
                throw new AuthorizationFailureException("MISSING_PERMISSION",
                    "only global administrators can change company grants");
            }
            candidates.validateCompanies(request.companyIds());
        }
        store.lockFormAuthorization(formId);
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        int updated = store.bumpAuthzVersion(formId, request.version());
        if (updated != 1) {
            throw new BizException("FORM_GRANT_VERSION_CONFLICT",
                "form usage scope or maintainers were changed by another user");
        }
        store.deleteSubjects(formId, admin);
        Long actorId = PrincipalHolder.current().orElseThrow().userId();
        if (request.companyIds() != null) {
            store.deleteCompanySubjects(formId);
            request.companyIds().forEach(companyId -> store.insertCompany(formId, companyId, actorId));
        }
        request.userIds().forEach(userId -> store.insertUser(formId, userId, actorId));
        if (admin) {
            request.roleIds().forEach(roleId -> store.insertRole(formId, roleId, actorId));
        }
        request.departmentIds().forEach(departmentId -> store.insertDepartment(formId, departmentId, actorId));
        List<Long> effectiveRoleIds = admin ? request.roleIds().stream().sorted().toList()
            : store.roleSubjectIds(formId);
        auditService.success("form.authorization.update", "FORM", formId,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", request.companyIds() == null
                ? List.of("userIds", "roleIds", "departmentIds")
                : List.of("userIds", "roleIds", "departmentIds", "companyIds")),
            java.util.Map.of("userCount", request.userIds().size(),
                "roleCount", effectiveRoleIds.size(),
                "departmentCount", request.departmentIds().size(),
                "companyCount", store.companyIds(formId).size()));
        return new FormGrantDto(request.version() + 1,
            request.userIds().stream().sorted().toList(),
            effectiveRoleIds, request.departmentIds().stream().sorted().toList(),
            store.selectedUsers(formId), store.selectedRoles(formId), store.selectedDepartments(formId),
            store.companyIds(formId), store.selectedCompanies(formId));
    }

    @Transactional
    public void grantCreator(long formId, long creatorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING
            """, formId, creatorId, creatorId);
        jdbcTemplate.update("""
            INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
            VALUES (?, ?, ?)
            ON CONFLICT (form_def_id, user_id) DO NOTHING
            """, formId, creatorId, creatorId);
    }

    public FormMaintainerDto getMaintainers(long formId) {
        return maintainers.getMaintainers(formId);
    }

    @Transactional
    public FormMaintainerDto replaceMaintainers(long formId,
                                                FormMaintainerWriteRequest request) {
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        if (request == null || request.version() == null) {
            throw new BizException("FORM_MAINTAINER_VERSION_REQUIRED",
                "maintainer version is required");
        }
        if (request.userIds().isEmpty()) {
            throw new BizException("FORM_MAINTAINER_REQUIRED",
                "at least one active maintainer is required");
        }
        store.lockFormAuthorization(formId);
        maintainers.validateMaintainers(request.userIds());
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        int updated = store.bumpAuthzVersion(formId, request.version());
        if (updated != 1) {
            throw new BizException("FORM_MAINTAINER_VERSION_CONFLICT",
                "form maintainers were changed by another user");
        }
        maintainers.deleteMaintainers(formId);
        long actorId = PrincipalHolder.current().orElseThrow().userId();
        request.userIds().forEach(userId -> maintainers.insertMaintainer(formId, userId, actorId));
        auditService.success("form.maintainer.update", "FORM", formId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("maintainerIds")),
            Map.of("maintainerCount", request.userIds().size()));
        List<GrantUser> users = maintainers.selectedMaintainers(formId);
        return new FormMaintainerDto(request.version() + 1,
            users.stream().map(GrantUser::id).toList(), users);
    }

    public record FormGrantDto(int version, List<Long> userIds, List<Long> roleIds,
                               List<Long> departmentIds, List<GrantUser> users,
                               List<GrantRole> roles,
                               List<GrantDepartment> departments,
                               List<Long> companyIds, List<GrantCompany> companies) { }
    public record FormGrantCandidates(List<GrantRole> roles,
                                      List<GrantDepartment> departments,
                                      List<GrantCompany> companies) { }
    public record GrantCompany(long id, String name) { }
    public record GrantUser(long id, String username, String displayName, String employeeNo,
                            Long departmentId, String departmentName) { }
    public record GrantRole(long id, String code, String name) { }
    public record GrantDepartment(long id, Long parentId, String name) { }
    public record GrantUserPage(List<GrantUser> items, long total, int page, int size) { }
    public record FormGrantWriteRequest(Integer version, Set<Long> userIds,
                                        Set<Long> roleIds, Set<Long> departmentIds,
                                        Set<Long> companyIds) {
        public FormGrantWriteRequest(Integer version, Set<Long> userIds,
                                     Set<Long> roleIds, Set<Long> departmentIds) {
            this(version, userIds, roleIds, departmentIds, null);
        }
        public FormGrantWriteRequest {
            userIds = userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
            roleIds = roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds);
            departmentIds = departmentIds == null
                ? Set.of() : new LinkedHashSet<>(departmentIds);
            companyIds = companyIds == null ? null : new LinkedHashSet<>(companyIds);
        }
    }
    public record FormMaintainerDto(int version, List<Long> userIds, List<GrantUser> users) { }
    public record FormMaintainerWriteRequest(Integer version, Set<Long> userIds) {
        public FormMaintainerWriteRequest {
            userIds = userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
        }
    }
}
