package com.antflow.authz;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
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
