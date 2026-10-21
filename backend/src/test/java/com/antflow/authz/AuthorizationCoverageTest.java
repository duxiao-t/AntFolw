package com.antflow.authz;

import com.antflow.auth.SecurityConfig;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把规范变成可执行约束：
 * 1) 每个 REST 端点必须显式声明鉴权形态；
 * 2) 注解引用的能力码必须存在于 {@link PermissionCatalog}；
 * 3) 所有 {@link PermissionCodes} 常量必须登记在目录里；
 * 4) 移动端端点不得使用管理端入口判据（否则移动端会被 console:access 打死）。
 */
class AuthorizationCoverageTest {
    private static final Pattern CONSOLE_AUTHORITY =
        Pattern.compile("@authz\\.console\\('([^']+)'");
    private static final Pattern CAPABILITY_AUTHORITY =
        Pattern.compile("@authz\\.capability\\('([^']+)'");

    @Test
    void everyEndpointDeclaresAuthorization() throws Exception {
        List<String> undeclared = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            if (declaresAuthorization(controller)) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (isEndpoint(method) && !declaresAuthorization(method)) {
                    undeclared.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(undeclared)
            .as("every REST endpoint must declare an authorization form")
            .isEmpty();
    }

    @Test
    void annotatedCapabilitiesExistInCatalog() throws Exception {
        List<String> unknown = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
                if (annotation == null) {
                    continue;
                }
                for (Pattern pattern : List.of(CONSOLE_AUTHORITY, CAPABILITY_AUTHORITY)) {
                    Matcher matcher = pattern.matcher(annotation.value());
                    while (matcher.find()) {
                        if (!PermissionCatalog.isKnown(matcher.group(1))) {
                            unknown.add(controller.getSimpleName() + "#" + method.getName()
                                + " -> " + matcher.group(1));
                        }
                    }
                }
            }
        }
        assertThat(unknown).isEmpty();
    }

    /**
     * 免登录白名单与 {@code @PublicEndpoint} 必须一一对账。
     *
     * <p>为什么要这条：注解没有运行时切面，真正放行由 {@code SecurityConfig} 里硬编码的
     * permitAll 路径决定。只校验"注解存在"的话，把新端点写进 {@code /api/public/**}
     * 就会静默变成匿名可访问，而注解仍写着"需要登录"。
     *
     * <p>两件事一起钉：
     * <ol>
     *   <li>每个 {@code @PublicEndpoint} 端点都必须落在声明的放行集合内；</li>
     *   <li>放行集合本身被冻结——新增放行必须显式改这里的期望值，改动会被 review 看到。</li>
     * </ol>
     */
    @Test
    void publicEndpointsMatchDeclaredPermitAllPatterns() throws Exception {
        AntPathMatcher matcher = new AntPathMatcher();
        List<String> uncovered = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            boolean classLevelPublic = controller.isAnnotationPresent(PublicEndpoint.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpoint(method)) {
                    continue;
                }
                if (!classLevelPublic && !method.isAnnotationPresent(PublicEndpoint.class)) {
                    continue;
                }
                String path = endpointPath(controller, method);
                boolean covered = SecurityConfig.PUBLIC_PATH_MATCHERS.stream()
                        .anyMatch(pattern -> matcher.match(pattern, path))
                    || SecurityConfig.PUBLIC_POST_ENDPOINTS.contains(path);
                if (!covered) {
                    uncovered.add(controller.getSimpleName() + "#" + method.getName() + " -> " + path);
                }
            }
        }
        assertThat(uncovered)
            .as("@PublicEndpoint 端点必须落在 SecurityConfig 声明的免登录路径里")
            .isEmpty();

        // 冻结清单：改动放行范围必须同时改这里（review 时一眼可见）。
        assertThat(SecurityConfig.PUBLIC_PATH_MATCHERS).containsExactlyElementsOf(
            List.of("/api/public/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**"));
        assertThat(SecurityConfig.PUBLIC_POST_ENDPOINTS).containsExactlyElementsOf(
            List.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout"));
    }

    /** 类级 {@code @RequestMapping} 与方法级映射拼出端点路径（方法级带全路径时以它为准）。 */
    private static String endpointPath(Class<?> controller, Method method) {
        // 必须用 findMergedAnnotation：@GetMapping/@PostMapping 的 value 是靠 @AliasFor 合并出来的，
        // 直接 getAnnotation(RequestMapping.class).value() 拿到的是空数组。
        String base = firstPath(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        String path = firstPath(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
        if (path == null || path.isBlank()) {
            return base == null ? "" : base;
        }
        if (base == null || base.isBlank() || path.startsWith(base)) {
            return path;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null) {
            return null;
        }
        String[] paths = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return paths.length == 0 ? null : paths[0];
    }

    @Test
    void mobileEndpointsNeverRequireConsoleEntry() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            if (!controller.getName().contains(".mobile.")) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
                if (annotation != null && (annotation.value().contains("@authz.console(")
                    || annotation.value().contains("consoleEntry"))) {
                    offenders.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(offenders)
            .as("mobile endpoints must not require the console entry capability")
            .isEmpty();
    }

    @Test
    void everyPermissionCodeConstantIsRegisteredInCatalog() throws Exception {
        List<String> unregistered = new ArrayList<>();
        for (Field field : PermissionCodes.class.getDeclaredFields()) {
            if (field.getType() != String.class) {
                continue;
            }
            String code = (String) field.get(null);
            if (!PermissionCatalog.isKnown(code)) {
                unregistered.add(field.getName() + "=" + code);
            }
        }
        assertThat(unregistered).isEmpty();
    }

    private static boolean declaresAuthorization(AnnotatedElement element) {
        return element.isAnnotationPresent(PreAuthorize.class)
            || element.isAnnotationPresent(AuthenticatedOnly.class)
            || element.isAnnotationPresent(PublicEndpoint.class);
    }

    private static boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(PatchMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider provider =
            new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> found = new ArrayList<>();
        for (var candidate : provider.findCandidateComponents("com.antflow")) {
            found.add(Class.forName(candidate.getBeanClassName()));
        }
        return found;
    }
}
