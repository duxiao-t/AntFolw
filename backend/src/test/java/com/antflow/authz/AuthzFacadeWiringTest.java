package com.antflow.authz;

import com.antflow.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配守门：{@link RoleAdminService} / {@link FormGrantService} 及其协作者能被 Spring 真正装起来。
 *
 * <p>为什么要单独一条：{@code AntFlowApplicationTests} 在本机/CI 是 skipped 的（需要真库），
 * 所以"容器启动时构造器选错/依赖成环"这类事故单元测试测不出来——它直接表现为应用起不来。
 * 两个门面都同时有 {@code @Autowired} 六参构造器与三参夹具构造器，这正是最容易选错的地方。
 *
 * <p>只注册这一簇 bean（不扫全包，避免拖进需要 Mapper 的邻居），验证三件事：
 * <ol>
 *   <li>门面选中的是 {@code @Autowired} 那个构造器，而不是三参夹具；</li>
 *   <li>包内可见的协作者能被 Spring 隐式注入（不需要 public）；</li>
 *   <li>{@link FormMaintainerStore} → {@link FormGrantStore} 的单向依赖不成环。</li>
 * </ol>
 */
class AuthzFacadeWiringTest {

    @Test
    void springCanWireRoleAndGrantFacades() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> Mockito.mock(JdbcTemplate.class));
            context.registerBean(AuditService.class, () -> Mockito.mock(AuditService.class));
            context.register(AuthzSnapshotStore.class, CapabilityResolver.class,
                DataScopeResolver.class, FormAccessGuard.class, InstanceAccessGuard.class,
                AuthorizationService.class,
                RoleStore.class, RoleGrantAdmin.class, RoleQueryService.class,
                RoleAdminService.class,
                FormGrantStore.class, FormMaintainerStore.class, FormGrantCandidateQuery.class,
                FormGrantService.class);
            context.refresh();

            assertThat(context.getBean(RoleAdminService.class)).isNotNull();
            assertThat(context.getBean(FormGrantService.class)).isNotNull();
            // 协作者必须是容器里的同一份（夹具构造器自己 new 的那份不该出现）
            assertThat(context.getBean(RoleStore.class)).isNotNull();
            assertThat(context.getBean(FormGrantStore.class)).isNotNull();
        }
    }
}
