package com.antflow.integration.wecom;

/**
 * t_wecom_config 的一行（原 WecomAppService.AppConfig）。
 * 由 WecomAppConfigStore 生产，token 缓存、免登、消息投递三方共用。
 */
record WecomAppConfig(long companyId, String corpId, int agentId,
                      String encryptedAgentSecret, boolean oauthEnabled,
                      boolean jsSdkEnabled, boolean messageEnabled) { }
