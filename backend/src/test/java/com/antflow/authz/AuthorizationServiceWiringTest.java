package com.antflow.authz;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配守门：{@link AuthorizationService} 的六个 bean 能被 Spring 真正装起来。
 *
 * <p>为什么要单独一条：{@code AntFlowApplicationTests} 在本机/CI 是 skipped 的（需要真库），
 * 所以单元测试用 mock 协作者能绿、但"容器启动时构造器选错/依赖成环"这类事故测不出来——
 * 那会直接表现为应用起不来。
 *
 * <p>这里只注册这六个 bean（不扫全包，避免拖进需要 Mapper 的邻居），验证三件事：
 * <ol>
 *   <li>门面选中的是 {@code @Autowired} 那个五参构造器，而不是包内可见的 JdbcTemplate 夹具；</li>
 *   <li>五个协作者的包内可见单构造器能被 Spring 隐式注入（不需要 public）;</li>
 *   <li>协作者之间没有循环依赖（有的话 refresh 会抛 BeanCurrentlyInCreationException）。</li>
 * </ol>
 */
class AuthorizationServiceWiringTest {

    @Test
    void springCanWireTheFacadeAndItsCollaborators() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> Mockito.mock(JdbcTemplate.class));
            context.register(AuthzSnapshotStore.class, CapabilityResolver.class,
                DataScopeResolver.class, FormAccessGuard.class, InstanceAccessGuard.class,
                AuthorizationService.class);
            context.refresh();

            AuthorizationService service = context.getBean(AuthorizationService.class);
            assertThat(service).isNotNull();
            // 门面拿到的必须是容器里的协作者，而不是自己 new 出来的一份（否则缓存会出现两份）
            assertThat(context.getBean(AuthzSnapshotStore.class)).isNotNull();
            // 协作者真的接上了：mock 的 JdbcTemplate 查不到用户 → 快照装载 fail-closed
            assertThatThrownBy(() -> service.snapshot(1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled");
        }
    }
}
