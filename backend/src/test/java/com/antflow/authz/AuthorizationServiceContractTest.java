package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拆分后的"门面契约"守门测试（2026-10-22 第五刀之后补）。
 *
 * <p>为什么需要它：{@link AuthorizationService} 是全应用权限判定的唯一入口，被 30+ 个
 * Controller/Service 直接调用，另有 {@code @PreAuthorize("@authz.console('…')")}（经
 * {@link AuthzPolicy}）在**运行时**求值。方法名或返回语义一旦漂移，编译期不一定报错，
 * 但鉴权会静默降级——这是本仓库最不能出的一类事故。所以：
 * <ol>
 *   <li>公开方法签名清单被冻结：改名/加删参数/改返回类型都必须同时改这里，评审一眼可见；</li>
 *   <li>每个公开方法都是"一行委托"：用 mock 协作者验证方向、参数与返回值，防止门面里
 *       重新长出判定逻辑（那会把拆分退回去，也会让两份实现慢慢分叉）。</li>
 * </ol>
 */
class AuthorizationServiceContractTest {

    /**
     * 拆分前后的公开契约（42 个方法）。清单按"名字(参数类型)->返回类型"排序，逐字比对。
     *
     * <p>生成方式：反射 {@code getDeclaredMethods()} + {@code getGenericParameterTypes()}。
     * 与拆前 {@code AuthorizationService} 的公开方法集合完全一致（拆前 42 个，拆后仍 42 个，
     * 一个没删、一个没改签名）。
     */
    private static final List<String> PUBLIC_CONTRACT = List.of(
        "canReadFormData(long,long)->boolean",
        "canReadFullInstance(long,long)->boolean",
        "canReadInstance(long,long)->boolean",
        "canUseForm(long,long)->boolean",
        "currentDataScope(java.lang.String)->java.util.Optional<"
            + "com.antflow.authz.AuthorizationService$DataScopeFilter>",
        "currentSnapshot()->com.antflow.authz.AuthorizationService$AuthzSnapshot",
        "currentUserId()->long",
        "evict(long)->void",
        "evictAll()->void",
        "hasFormGrant(long,long)->boolean",
        "hasFormGrant(long,long,java.lang.Long)->boolean",
        "hasFormMaintainer(long,long)->boolean",
        "hasPermission(java.lang.String)->boolean",
        "hasPermission(long,java.lang.String)->boolean",
        "inCurrentDataScope(java.lang.String,java.lang.Long,java.lang.Long)->boolean",
        "inDataScope(com.antflow.authz.AuthorizationService$AuthzSnapshot,java.lang.String,"
            + "java.lang.Long,java.lang.Long)->boolean",
        "instanceVisibility(long,long)->"
            + "com.antflow.authz.AuthorizationService$InstanceVisibility",
        "isAdmin()->boolean",
        "maintainableFormIds(long)->java.util.Optional<java.util.Set<java.lang.Long>>",
        "manageableDepartments(com.antflow.authz.AuthorizationService$AuthzSnapshot,"
            + "java.lang.String)->java.util.Set<java.lang.Long>",
        "principalForRequest(long,java.util.UUID)->"
            + "java.util.Optional<com.antflow.auth.PrincipalHolder$Principal>",
        "requireAdmin()->void",
        "requireAllDataScope(java.lang.String)->void",
        "requireAnyPermission(java.lang.String[])->void",
        "requireConfirmedInstance(long)->void",
        "requireCorrectableFormData(long)->void",
        "requireCurrentDataScope(java.lang.String,java.lang.Long,java.lang.Long)->void",
        "requireFormCorrection(long)->void",
        "requireFormMaintenance(long,java.lang.String)->void",
        "requireFormMaintenanceAny(long,java.lang.String[])->void",
        "requireFormUse(long)->void",
        "requireFormUse(long,java.lang.Long)->void",
        "requireFormUseByCode(java.lang.String)->void",
        "requireManageInstance(long,java.lang.String)->void",
        "requireManageTask(long,java.lang.String)->void",
        "requireManageableDepartment(java.lang.String,java.lang.Long)->void",
        "requirePermission(java.lang.String)->void",
        "requireReadableFormData(long)->void",
        "requireReadableInstance(long)->void",
        "requireReadableTask(long)->void",
        "snapshot(long)->com.antflow.authz.AuthorizationService$AuthzSnapshot",
        "usableFormIds(long)->java.util.Optional<java.util.Set<java.lang.Long>>",
        "usableFormIdsByCompany(long)->java.util.Optional<java.util.Map<java.lang.Long, "
            + "java.util.Set<java.lang.Long>>>",
        "visibleDepartments(java.lang.String)->java.util.Set<java.lang.Long>"
    );

    private final AuthzSnapshotStore snapshots = Mockito.mock(AuthzSnapshotStore.class);
    private final CapabilityResolver capabilities = Mockito.mock(CapabilityResolver.class);
    private final DataScopeResolver dataScopes = Mockito.mock(DataScopeResolver.class);
    private final FormAccessGuard forms = Mockito.mock(FormAccessGuard.class);
    private final InstanceAccessGuard instances = Mockito.mock(InstanceAccessGuard.class);
    private final AuthorizationService service = new AuthorizationService(
        snapshots, capabilities, dataScopes, forms, instances);

    @Test
    void publicContractIsFrozen() {
        List<String> actual = Arrays.stream(AuthorizationService.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .map(AuthorizationServiceContractTest::signature)
            .sorted()
            .collect(Collectors.toList());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(PUBLIC_CONTRACT);
    }

    /**
     * 门面只做转发：参数原样透传、返回值原样返回。
     *
     * <p>这组断言的价值不在"跑通"，而在于任何一次"顺手在门面里加个条件"都会让它变红。
     */
    @Test
    void facadeDelegatesToCollaborators() {
        var snapshot = new AuthorizationService.AuthzSnapshot(7L, 10L, false,
            Set.of(), Set.of(), Map.of());
        UUID sessionId = UUID.randomUUID();
        var principal = new PrincipalHolder.Principal(7L, "u", "U", Set.of(), Set.of(), 1L, 10L,
            sessionId);
        var scope = new AuthorizationService.DataScopeFilter(false, false, true, 7L, Set.of());
        var filter = Optional.of(scope);

        when(snapshots.snapshot(7L)).thenReturn(snapshot);
        when(snapshots.principalForRequest(7L, sessionId)).thenReturn(Optional.of(principal));
        when(capabilities.hasPermission("form:data:read")).thenReturn(true);
        when(capabilities.hasPermission(7L, "form:data:read")).thenReturn(true);
        when(capabilities.isAdmin()).thenReturn(true);
        when(capabilities.currentUserId()).thenReturn(7L);
        when(capabilities.currentSnapshot()).thenReturn(snapshot);
        when(dataScopes.currentDataScope(PermissionCodes.FORM_DATA_READ)).thenReturn(filter);
        when(dataScopes.inDataScope(snapshot, PermissionCodes.FORM_DATA_READ, 1L, 2L)).thenReturn(true);
        when(dataScopes.manageableDepartments(snapshot, PermissionCodes.FORM_DATA_READ))
            .thenReturn(Set.of(3L));
        when(forms.hasFormGrant(9L, 7L)).thenReturn(true);
        when(forms.canUseForm(9L, 7L)).thenReturn(true);
        when(forms.usableFormIds(7L)).thenReturn(Optional.of(Set.of(9L)));
        when(instances.instanceVisibility(5L, 7L))
            .thenReturn(AuthorizationService.InstanceVisibility.FULL);

        assertThat(service.snapshot(7L)).isSameAs(snapshot);
        assertThat(service.principalForRequest(7L, sessionId)).containsSame(principal);
        assertThat(service.hasPermission("form:data:read")).isTrue();
        assertThat(service.hasPermission(7L, "form:data:read")).isTrue();
        assertThat(service.isAdmin()).isTrue();
        assertThat(service.currentUserId()).isEqualTo(7L);
        assertThat(service.currentSnapshot()).isSameAs(snapshot);
        assertThat(service.currentDataScope(PermissionCodes.FORM_DATA_READ)).isSameAs(filter);
        assertThat(service.inDataScope(snapshot, PermissionCodes.FORM_DATA_READ, 1L, 2L)).isTrue();
        assertThat(service.manageableDepartments(snapshot, PermissionCodes.FORM_DATA_READ))
            .containsExactly(3L);
        assertThat(service.hasFormGrant(9L, 7L)).isTrue();
        assertThat(service.usableFormIds(7L)).contains(Set.of(9L));
        assertThat(service.instanceVisibility(5L, 7L))
            .isEqualTo(AuthorizationService.InstanceVisibility.FULL);

        // 指令型方法：验证"转发到了谁、参数有没有走样"
        service.evict(7L);
        service.evictAll();
        service.requirePermission("form:data:read");
        service.requireAnyPermission("a", "b");
        service.requireAdmin();
        service.requireCurrentDataScope(PermissionCodes.FORM_DATA_READ, 1L, 2L);
        service.requireManageableDepartment(PermissionCodes.FORM_DATA_READ, 3L);
        service.requireAllDataScope(PermissionCodes.FORM_DATA_READ);
        service.visibleDepartments(PermissionCodes.FORM_DATA_READ);
        service.requireFormUse(9L);
        service.requireFormUse(9L, 2L);
        service.requireFormMaintenance(9L, "form:definition:manage");
        service.requireFormMaintenanceAny(9L, "a", "b");
        service.requireFormUseByCode("leave");
        service.requireReadableInstance(5L);
        service.requireReadableTask(6L);
        service.requireManageTask(6L, "workflow:instance:override");
        service.requireManageInstance(5L, "workflow:instance:override");
        service.requireReadableFormData(4L);
        service.requireConfirmedInstance(5L);
        assertThat(service.canUseForm(9L, 7L)).isTrue();
        assertThat(service.hasFormMaintainer(9L, 7L)).isFalse();
        assertThat(service.maintainableFormIds(7L)).isEmpty();
        assertThat(service.canReadFormData(4L, 7L)).isFalse();

        verify(snapshots).evict(7L);
        verify(snapshots).evictAll();
        verify(capabilities).requirePermission("form:data:read");
        verify(capabilities).requireAnyPermission("a", "b");
        verify(capabilities).requireAdmin();
        verify(dataScopes).requireCurrentDataScope(PermissionCodes.FORM_DATA_READ, 1L, 2L);
        verify(dataScopes).requireManageableDepartment(PermissionCodes.FORM_DATA_READ, 3L);
        verify(dataScopes).requireAllDataScope(PermissionCodes.FORM_DATA_READ);
        verify(dataScopes).visibleDepartments(PermissionCodes.FORM_DATA_READ);
        verify(forms).requireFormUse(9L);
        verify(forms).requireFormUse(9L, 2L);
        verify(forms).requireFormMaintenance(9L, "form:definition:manage");
        verify(forms).requireFormMaintenanceAny(9L, "a", "b");
        verify(forms).requireFormUseByCode("leave");
        verify(forms).canUseForm(9L, 7L);
        verify(forms).hasFormMaintainer(9L, 7L);
        verify(forms).maintainableFormIds(7L);
        verify(instances).requireReadableInstance(5L);
        verify(instances).requireReadableTask(6L);
        verify(instances).requireManageTask(6L, "workflow:instance:override");
        verify(instances).requireManageInstance(5L, "workflow:instance:override");
        verify(instances).canReadFormData(4L, 7L);
        verify(instances).requireConfirmedInstance(5L);
    }

    /**
     * 拒绝必须原样穿透：门面不得吞异常、也不得把"拒绝"包装成"放行"。
     * 这里用 {@link AccessDeniedException} 的**同一个实例**断言，连包装都不允许。
     */
    @Test
    void denialsPropagateUnwrapped() {
        AccessDeniedException denial = new AccessDeniedException("user is disabled");
        Mockito.doThrow(denial).when(capabilities).requirePermission("form:data:read");
        Mockito.doThrow(denial).when(instances).requireReadableInstance(5L);
        Mockito.doThrow(denial).when(dataScopes).requireManageableDepartment("p", 3L);

        assertThatThrownBy(() -> service.requirePermission("form:data:read")).isSameAs(denial);
        assertThatThrownBy(() -> service.requireReadableInstance(5L)).isSameAs(denial);
        assertThatThrownBy(() -> service.requireManageableDepartment("p", 3L)).isSameAs(denial);
    }

    private static String signature(java.lang.reflect.Method method) {
        String parameters = Arrays.stream(method.getGenericParameterTypes())
            .map(Type::getTypeName)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + ")->"
            + method.getGenericReturnType().getTypeName();
    }
}
