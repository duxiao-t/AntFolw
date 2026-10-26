package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全应用权限判定的**唯一对外入口**：能力码、数据范围、表单授权、实例可见性。
 *
 * <p>2026-10-22 按"变化原因"拆开（809 行 / 67 方法 → 约 270 行 / 43 方法），本类只剩一行委托：
 * <ul>
 *   <li>{@link AuthzSnapshotStore}：快照装载与缓存失效（缓存/代次的竞态都在那里）</li>
 *   <li>{@link CapabilityResolver}：能力码判定（"这个功能能不能用"）</li>
 *   <li>{@link DataScopeResolver}：公司/部门数据范围（"能看到哪些行"）</li>
 *   <li>{@link FormAccessGuard}：表单使用授权与维护人</li>
 *   <li>{@link InstanceAccessGuard}：流程实例/表单数据的可见性与可管理判定</li>
 * </ul>
 *
 * <p><b>改这个类的两条纪律：</b>
 * <ol>
 *   <li>不在这里写判定逻辑——加一行 if 就等于把拆分退回去。新规则加到对应协作者里，这里只补委托。</li>
 *   <li>公开方法名与签名是契约：{@code @PreAuthorize("@authz.console('…')")} 在**运行时**经
 *       {@link AuthzPolicy} 求值，最终落在 {@code hasPermission(String)} 与 {@code isAdmin()} 上；
 *       其余方法由各 Controller/Service 在编译期直接调用。前者改名/改语义不会报错，只会静默失效。</li>
 * </ol>
 */
@Service
public class AuthorizationService {
    private final AuthzSnapshotStore snapshots;
    private final CapabilityResolver capabilities;
    private final DataScopeResolver dataScopes;
    private final FormAccessGuard forms;
    private final InstanceAccessGuard instances;

    /**
     * 生产装配：五个协作者由 Spring 注入。
     *
     * <p>为什么显式写而不是 {@code @RequiredArgsConstructor}：本类同时保留一个"从 JdbcTemplate
     * 装整条链"的构造器（单测与装配夹具用），两个构造器必须指明哪一个给容器用——否则 Spring
     * 会因为无法在两者之间选择而启动失败。协作者之间不回调本类，所以不存在循环依赖。
     */
    @Autowired
    public AuthorizationService(AuthzSnapshotStore snapshots, CapabilityResolver capabilities,
                                DataScopeResolver dataScopes, FormAccessGuard forms,
                                InstanceAccessGuard instances) {
        this.snapshots = snapshots;
        this.capabilities = capabilities;
        this.dataScopes = dataScopes;
        this.forms = forms;
        this.instances = instances;
    }

    /** 单测/装配夹具：从 JdbcTemplate 建出整条链，顺序与依赖方向一致（store → 判定 → 资源守卫）。 */
    AuthorizationService(JdbcTemplate jdbcTemplate) {
        this.snapshots = new AuthzSnapshotStore(jdbcTemplate);
        this.capabilities = new CapabilityResolver(snapshots);
        this.dataScopes = new DataScopeResolver(jdbcTemplate, snapshots, capabilities);
        this.forms = new FormAccessGuard(jdbcTemplate, snapshots, capabilities, dataScopes);
        this.instances = new InstanceAccessGuard(jdbcTemplate, snapshots, capabilities, dataScopes);
    }

    public Optional<PrincipalHolder.Principal> principalForRequest(long userId, UUID sessionId) {
        return snapshots.principalForRequest(userId, sessionId);
    }

    public AuthzSnapshot snapshot(long userId) {
        return snapshots.snapshot(userId);
    }

    public void evict(long userId) {
        snapshots.evict(userId);
    }

    /**
     * 全局授权变更（菜单编排、能力目录）后一次性失效所有快照。
     *
     * <p>代次与竞态的处理在 {@link AuthzSnapshotStore#evictAll()}：不在这里做，是因为
     * 缓存与代次必须由同一个对象持有，否则"先递增再清空"的先后顺序会被拆到两个类里，
     * 任何一方单独改动都会静默失效。
     */
    public void evictAll() {
        snapshots.evictAll();
    }

    public void requirePermission(String permission) {
        capabilities.requirePermission(permission);
    }

    public void requireAnyPermission(String... permissions) {
        capabilities.requireAnyPermission(permissions);
    }

    public void requireAdmin() {
        capabilities.requireAdmin();
    }

    public boolean hasPermission(String permission) {
        return capabilities.hasPermission(permission);
    }

    /**
     * 指定用户（而不是当前请求主体）是否持有该能力。
     * 服务层按 userId 判定时必须用这个——与 canUseForm/hasFormGrant 一样以入参用户为准，
     * 否则"按 A 用户查数据"和"用当前主体的能力判定"会错配。
     */
    public boolean hasPermission(long userId, String permission) {
        return capabilities.hasPermission(userId, permission);
    }

    public boolean isAdmin() {
        return capabilities.isAdmin();
    }

    public long currentUserId() {
        return capabilities.currentUserId();
    }

    public AuthzSnapshot currentSnapshot() {
        return capabilities.currentSnapshot();
    }

    /**
     * 供数据权限拦截器使用：某能力在当前请求下的行级过滤条件。
     *
     * <p>返回空表示"无请求主体"（系统内部任务），调用方应跳过注入；
     * admin 表示管理员旁路；unrestricted 表示非管理员的 ALL 范围；
     * 既无 selfAllowed 又无 departmentIds 表示该能力缺失 → 调用方应注入 1=0（fail-closed）。
     */
    public Optional<DataScopeFilter> currentDataScope(String permissionCode) {
        return dataScopes.currentDataScope(permissionCode);
    }

    public record DataScopeFilter(boolean admin, boolean unrestricted, boolean selfAllowed,
                                  long userId, Set<Long> departmentIds, Long companyId) {
    }

    public boolean inCurrentDataScope(String permission, Long ownerId, Long departmentId) {
        return dataScopes.inCurrentDataScope(permission, ownerId, departmentId);
    }

    public void requireCurrentDataScope(String permission, Long ownerId, Long departmentId) {
        dataScopes.requireCurrentDataScope(permission, ownerId, departmentId);
    }

    public Set<Long> visibleDepartments(String permission) {
        return dataScopes.visibleDepartments(permission);
    }

    public void requireManageableDepartment(String permission, Long departmentId) {
        dataScopes.requireManageableDepartment(permission, departmentId);
    }

    public void requireAllDataScope(String permission) {
        dataScopes.requireAllDataScope(permission);
    }

    public boolean hasFormGrant(long formId, long userId) {
        return forms.hasFormGrant(formId, userId);
    }

    /** companyId must come from the authenticated department or an owned draft snapshot. */
    public boolean hasFormGrant(long formId, long userId, Long companyId) {
        return forms.hasFormGrant(formId, userId, companyId);
    }

    /**
     * 该用户有使用授权的表单 id 集合，用于列表类的批量过滤。
     * 返回空表示"不需要过滤"（管理员），调用方直接放行——不是"一个都没有"。
     */
    public Optional<Set<Long>> usableFormIds(long userId) {
        return forms.usableFormIds(userId);
    }

    /** Batch draft authorization uses each stored company, rather than today's department. */
    public Optional<Map<Long, Set<Long>>> usableFormIdsByCompany(long userId) {
        return forms.usableFormIdsByCompany(userId);
    }

    /** 已发布表单的使用入口：原子能力与使用范围必须同时满足。 */
    public void requireFormUse(long formId) {
        forms.requireFormUse(formId);
    }

    public void requireFormUse(long formId, Long companyId) {
        forms.requireFormUse(formId, companyId);
    }

    public boolean canUseForm(long formId, long userId) {
        return forms.canUseForm(formId, userId);
    }

    public boolean hasFormMaintainer(long formId, long userId) {
        return forms.hasFormMaintainer(formId, userId);
    }

    /**
     * 该用户维护的表单 id 集合，用于列表类的批量过滤。
     * 返回空表示"不需要过滤"（管理员），调用方直接放行——不是"什么都不维护"。
     */
    public Optional<Set<Long>> maintainableFormIds(long userId) {
        return forms.maintainableFormIds(userId);
    }

    /** 模板操作入口：非管理员必须同时是维护人并持有对应原子能力。 */
    public void requireFormMaintenance(long formId, String permission) {
        forms.requireFormMaintenance(formId, permission);
    }

    public void requireFormMaintenanceAny(long formId, String... permissions) {
        forms.requireFormMaintenanceAny(formId, permissions);
    }

    /**
     * 修正已提交表单数据的入口校验（表单级）：管理员放行，
     * 其余必须持有 {@code form:data:correct} 且是该表单维护者。
     * 还需配合 {@link #requireCorrectableFormData(long)} 做数据级（公司归属）校验。
     */
    public void requireFormCorrection(long formId) {
        forms.requireFormCorrection(formId);
    }

    /** 修正的数据级校验：归属未确认的单据仅管理员可碰。 */
    public void requireCorrectableFormData(long formDataId) {
        instances.requireCorrectableFormData(formDataId);
    }

    /**
     * 发起/填报入口统一使用：表单必须已发布且当前用户具备表单使用授权。
     * 未授权时返回资源不存在，避免泄露表单是否存在。
     */
    public void requireFormUseByCode(String code) {
        forms.requireFormUseByCode(code);
    }

    public void requireReadableInstance(long instanceId) {
        instances.requireReadableInstance(instanceId);
    }

    public boolean canReadInstance(long instanceId, long userId) {
        return instances.canReadInstance(instanceId, userId);
    }

    public boolean canReadFullInstance(long instanceId, long userId) {
        return instances.canReadFullInstance(instanceId, userId);
    }

    public InstanceVisibility instanceVisibility(long instanceId, long userId) {
        return instances.instanceVisibility(instanceId, userId);
    }

    public void requireReadableTask(long taskId) {
        instances.requireReadableTask(taskId);
    }

    public void requireManageTask(long taskId, String permission) {
        instances.requireManageTask(taskId, permission);
    }

    public void requireManageInstance(long instanceId, String permission) {
        instances.requireManageInstance(instanceId, permission);
    }

    public void requireReadableFormData(long formDataId) {
        instances.requireReadableFormData(formDataId);
    }

    public boolean canReadFormData(long formDataId, long userId) {
        return instances.canReadFormData(formDataId, userId);
    }

    /** Assigned actions retain their existing actor checks, but cannot operate on uncertain attribution. */
    public void requireConfirmedInstance(long instanceId) {
        instances.requireConfirmedInstance(instanceId);
    }

    public boolean inDataScope(AuthzSnapshot snapshot, String permission,
                               Long ownerId, Long departmentId) {
        return dataScopes.inDataScope(snapshot, permission, ownerId, departmentId);
    }

    public Set<Long> manageableDepartments(AuthzSnapshot snapshot, String permission) {
        return dataScopes.manageableDepartments(snapshot, permission);
    }

    // 嵌套值类型留在门面：FormGrantService / RoleAdminService / UserService / 移动端与
    // PostgresTransactionalIntegrityIntegrationTest 都按 `AuthorizationService.Xxx` 引用它们，
    // 搬家会波及 authz 包外的文件（本次拆分禁止改边界外代码）。协作者只把它们当不可变数据载体，
    // 不持有 AuthorizationService 引用。
    public record RoleGrant(long roleId, String code, DataScope dataScope,
                            Set<Long> customDepartmentIds) { }

    public record AuthzSnapshot(long userId, Long departmentId, boolean admin,
                                Set<String> roleCodes, Set<String> permissions,
                                Map<String, List<RoleGrant>> permissionRoles) { }

    public enum InstanceVisibility { NONE, SUMMARY, FULL }
}
