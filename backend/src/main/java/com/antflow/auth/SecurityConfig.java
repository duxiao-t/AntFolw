package com.antflow.auth;

import com.antflow.common.IdempotencyFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity   // enables @PreAuthorize("hasRole('admin')") on controllers
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final LoginRateLimitFilter loginRateLimitFilter;
    private final IdempotencyFilter idempotencyFilter;

    @Bean
    public SecurityFilterChain filter(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(c -> {
                CorsConfiguration cfg = new CorsConfiguration();
                String raw = jwtProperties.getSecret() == null
                    ? "http://localhost:8000"
                    : "http://localhost:8000";
                List<String> allowed = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
                cfg.setAllowedOrigins(allowed);
                cfg.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS","PATCH"));
                cfg.setAllowedHeaders(List.of("*"));
                cfg.setAllowCredentials(true);
                UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
                src.registerCorsConfiguration("/**", cfg);
                c.configurationSource(src);
            })
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            // Order matters: idempotency check needs the principal set by JwtAuthFilter,
            // so register idempotency AFTER jwt.
            .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(idempotencyFilter, JwtAuthFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
