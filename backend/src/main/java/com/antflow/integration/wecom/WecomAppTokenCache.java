package com.antflow.integration.wecom;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 企微 access_token / jsapi_ticket 的进程内缓存（原 WecomAppService#session / #ticket）。
 *
 * 易错点：
 * 1. 缓存键是 company_id，但有效性还要比对**密钥指纹**：换 secret 后必须重新 connect，不能复用旧 token。
 * 2. 重建 session 时必须清掉该公司的 ticket —— ticket 与 access_token 同源，token 换了 ticket 可能已失效。
 * 3. ticket 的缓存时长固定 7000s（企微有效期 7200s，留 200s 余量），不要改成"永不失效"。
 */
@Component
class WecomAppTokenCache {
    private final WecomClient client;
    private final WecomSecretCipher cipher;
    private final Map<Long, CachedSession> appSessions = new ConcurrentHashMap<>();
    private final Map<Long, CachedTicket> tickets = new ConcurrentHashMap<>();

    WecomAppTokenCache(WecomClient client, WecomSecretCipher cipher) {
        this.client = client;
        this.cipher = cipher;
    }

    WecomClient.Session session(WecomAppConfig config) {
        CachedSession cached = appSessions.get(config.companyId());
        if (cached != null && cached.secretFingerprint().equals(config.encryptedAgentSecret())) {
            return cached.session();
        }
        WecomClient.Session created = client.connect(config.corpId(),
            cipher.decrypt(config.encryptedAgentSecret(), "wecom-agent:" + config.companyId()));
        appSessions.put(config.companyId(), new CachedSession(config.encryptedAgentSecret(), created));
        tickets.remove(config.companyId());
        return created;
    }

    String ticket(WecomAppConfig config) {
        CachedTicket cached = tickets.get(config.companyId());
        if (cached != null && cached.secretFingerprint().equals(config.encryptedAgentSecret())
            && cached.expiresAt().isAfter(Instant.now())) return cached.ticket();
        String value = client.jsapiTicket(session(config));
        tickets.put(config.companyId(), new CachedTicket(config.encryptedAgentSecret(), value,
            Instant.now().plusSeconds(7000)));
        return value;
    }

    private record CachedSession(String secretFingerprint, WecomClient.Session session) { }
    private record CachedTicket(String secretFingerprint, String ticket, Instant expiresAt) { }
}
