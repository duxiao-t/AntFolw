package com.antflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {
    private static final long TTL_HOURS = 24;

    private final IdempotencyRecordMapper mapper;
    private final Map<String, CachedResponse> fallback = new ConcurrentHashMap<>();

    /** Unit-test fallback; production uses the database-backed constructor. */
    public IdempotencyService(ObjectMapper ignored) { this.mapper = null; }

    @Autowired
    public IdempotencyService(ObjectMapper ignored, IdempotencyRecordMapper mapper) {
        this.mapper = mapper;
    }

    public record CachedResponse(int status, String body) {}
    public record Claim(IdempotencyRecord record, CachedResponse replay, String conflict) {
        public boolean claimed() { return record != null && "PROCESSING".equals(record.getStatus()); }
    }

    public Claim claim(long userId, String method, String path, String key, byte[] body) {
        if (key == null || key.isBlank()) return new Claim(null, null, null);
        String hash = hash(body);
        if (mapper == null) {
            String scope = scope(userId, method, path, key);
            CachedResponse cached = fallback.get(scope);
            return cached == null
                ? new Claim(new IdempotencyRecord(), null, null)
                : new Claim(null, cached, null);
        }
        mapper.deleteExpired();
        boolean acquired = mapper.tryClaim(userId, method, path, key, hash,
            OffsetDateTime.now().plusHours(TTL_HOURS)) > 0;
        IdempotencyRecord row = mapper.find(userId, method, path, key);
        if (row == null) return new Claim(null, null, "IDEMPOTENCY_IN_PROGRESS");
        if (!hash.equals(row.getRequestHash())) {
            return new Claim(null, null, "IDEMPOTENCY_CONFLICT");
        }
        if ("SUCCEEDED".equals(row.getStatus())) {
            return new Claim(null, new CachedResponse(row.getResponseStatus(), row.getResponseBody()), null);
        }
        if ("PROCESSING".equals(row.getStatus())) return acquired
            ? new Claim(row, null, null)
            : new Claim(null, null, "IDEMPOTENCY_IN_PROGRESS");
        return new Claim(null, null, "IDEMPOTENCY_IN_PROGRESS");
    }

    public void succeed(Claim claim, int status, String body, byte[] requestBody) {
        if (claim == null || claim.record() == null) return;
        if (mapper == null) return;
        mapper.markSucceeded(claim.record().getId(), hash(requestBody), status, body);
    }

    public void fail(Claim claim, byte[] requestBody) {
        if (claim == null || claim.record() == null) return;
        if (mapper == null) return;
        mapper.markFailed(claim.record().getId(), hash(requestBody));
    }

    public CachedResponse executeOrReplay(String key, long userId,
                                          java.util.function.Supplier<CachedResponse> action) {
        if (mapper != null) throw new IllegalStateException("use claim through IdempotencyFilter");
        if (key == null || key.isBlank()) return action.get();
        String fullKey = scope(userId, "POST", "", key);
        CachedResponse cached = fallback.get(fullKey);
        if (cached != null) return cached;
        CachedResponse fresh = action.get();
        if (fresh.status() >= 200 && fresh.status() < 300) fallback.putIfAbsent(fullKey, fresh);
        return fresh;
    }

    public CachedResponse peek(String fullKey) { return fallback.get(fullKey); }
    public void store(String fullKey, CachedResponse resp) { if (resp.status() >= 200 && resp.status() < 300) fallback.putIfAbsent(fullKey, resp); }
    public void clearFallback() { fallback.clear(); }

    public static String hash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String scope(long userId, String method, String path, String key) {
        return userId + ":" + method + ":" + path + ":" + key;
    }
}
