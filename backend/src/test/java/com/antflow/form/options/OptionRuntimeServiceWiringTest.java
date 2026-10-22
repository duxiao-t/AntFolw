package com.antflow.form.options;

import com.antflow.authz.AuthorizationService;
import com.antflow.process.DefinitionVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配守门：{@link OptionRuntimeService} 与五个协作者能被 Spring 真正装起来。
 *
 * <p>为什么要单独一条：{@code AntFlowApplicationTests} 在本机/CI 是 skipped 的（需要真库），
 * "构造器缺一个依赖 / 协作者没被扫描到"这类事故在编译期看不出来，只在启动时炸。
 *
 * <p>顺带钉住一条边界：{@link OptionSchemaSupport} 是纯静态工具（不是 bean），
 * 它不该出现在容器里——出现就说明有人把它写成了 {@code @Component}。
 */
class OptionRuntimeServiceWiringTest {

    @Test
    void springCanWireTheRuntimeFacadeAndItsCollaborators() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> Mockito.mock(JdbcTemplate.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(AuthorizationService.class,
                () -> Mockito.mock(AuthorizationService.class));
            context.registerBean(DefinitionVersionRepository.class,
                () -> Mockito.mock(DefinitionVersionRepository.class));
            context.register(OptionSchemaResolver.class, OptionSourceBinding.class,
                OptionValueValidator.class, OptionReferenceGuard.class, OptionRuntimeService.class);
            context.refresh();

            assertThat(context.getBean(OptionRuntimeService.class)).isNotNull();
            assertThat(context.getBean(OptionSourceBinding.class)).isNotNull();
            assertThat(context.getBeansOfType(OptionSchemaSupport.class)).isEmpty();
        }
    }
}
