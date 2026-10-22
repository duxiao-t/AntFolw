package com.antflow.auth;

/**
 * t_oidc_provider 的一行（原 OidcService.Provider）。
 * 由 OidcService 读取、OidcTokenClient 与 OidcIdentityBinder 消费；三处共用同一形状，避免互相依赖具体实现类。
 */
record OidcProvider(long id, String code, String issuerUri, String clientId,
                    String encryptedSecret, String clientAuthMethod, String scopes,
                    String matchClaim, String matchField, boolean enabled) { }
