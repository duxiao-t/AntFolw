package com.antflow.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 端点只需要"已登录"，不需要任何能力点（自助类接口，如 /api/auth/me、会话管理）。
 * 与 {@link PublicEndpoint} 一起构成端点鉴权声明的四种形态，供覆盖率测试校验。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedOnly {
}
