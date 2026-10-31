package com.antflow.auth;

import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditDenialFilter;
import com.antflow.audit.RequestIdFilter;
import com.antflow.common.IdempotencyFilter;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 免登录放行的**前缀集合**（除下面三个 POST 认证端点外的全部匿名入口）。
     *
     * <p>手工注解（{@code @AuthenticatedOnly}/{@code @PublicEndpoint}）只是声明，没有运行时切面——
     * 真正决定放行的是这里。改这个列表必须同时改 {@code AuthorizationCoverageTest} 的冻结断言，
     * 否则就出现"注解说需要登录、实际匿名可访问"的漂移（见 code-review-2026-10-17 B6）。
     */
    public static final List<String> PUBLIC_PATH_MATCHERS = List.of(
        "/api/public/**",
        // 只放行实际暴露的只读探针，不再用 /actuator/** 通配放行（防止将来开启 env/heapdump 等端点后匿名可访问）。
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs/**", "/swagger-ui/**");

    /** 免登录的 POST 端点：登录/刷新/登出（会话与 CSRF 语义自带，不需要 JWT）。 */
    public static final List<String> PUBLIC_POST_ENDPOINTS =
        List.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout");

    private final JwtService jwtService;
    private final AuthSessionService authSessionService;
    private final AuthorizationService authorizationService;
    private final CorsProperties corsProperties;
    private final LoginRateLimitFilter loginRateLimitFilter;
    private final IdempotencyFilter idempotencyFilter;
    private final RequestIdFilter requestIdFilter;
    private final AuditDenialFilter auditDenialFilter;

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(requestIdFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AuditDenialFilter> auditDenialFilterRegistration() {
        FilterRegistrationBean<AuditDenialFilter> registration = new FilterRegistrationBean<>(auditDenialFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration() {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(idempotencyFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filter(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {
                CorsConfiguration corsConfiguration = new CorsConfiguration();
                corsConfiguration.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
                corsConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
                // 显式白名单而非 "*"：凭证模式（allowCredentials=true）下不放开任意请求头（F10）。
                corsConfiguration.setAllowedHeaders(List.of(
                    "Authorization", "Content-Type", "Accept",
                    "Idempotency-Key", "X-CSRF-Token", "X-Requested-With", "X-Request-Id"));
                corsConfiguration.setAllowCredentials(true);
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", corsConfiguration);
                cors.configurationSource(source);
            })
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(registry -> registry
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS.toArray(String[]::new)).permitAll()
                .requestMatchers(PUBLIC_PATH_MATCHERS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"authentication required\"}");
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"access denied\"}");
                }))
            // Order matters: idempotency check needs the principal set by JwtAuthFilter,
            // so register idempotency AFTER jwt.
            .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(requestIdFilter, LoginRateLimitFilter.class)
            .addFilterBefore(new JwtAuthFilter(jwtService, authSessionService, authorizationService), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(idempotencyFilter, JwtAuthFilter.class)
            .addFilterAfter(auditDenialFilter, IdempotencyFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .build();
    }
}
