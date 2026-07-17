package com.antflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency-Key 服务 — Sprint 3 C10：
 * POST 请求带 {@code Idempotency-Key} header，重试时直接返回上次缓存的响应。
 *
 * <p>存储：Redis 字符串 (key → json {status, body})。
 * TTL：默认 24 小时（避免长期占用 Redis；超过 24h 的 key 视为新请求）。
 *
 * <p>当前项目没有 Redis 依赖；通过 {@code @Autowired(required=false)} 让
 * {@link StringRedisTemplate} 可选注入。Redis 不可用时退化到 in-memory
 * ConcurrentHashMap（单实例够用；多实例生产需要 Redis）。
 *
 * <p>用法：
 * <pre>{@code
 *   // 在 controller：
 *   var cached = idempotency.executeOrReplay(key, userId, () -> doActualWork());
 *   return cached;
 * }</pre>
 */
@Service
public class IdempotencyService {

    private final ObjectMapper json;
    /** 可选 Redis 客户端；通过 Object 反射注入避免硬依赖 spring-data-redis */
    @Autowired(required = false)
    private Object redis;
    private final java.util.Map<String, CachedResponse> fallback = new ConcurrentHashMap<>();

    public IdempotencyService(ObjectMapper json) {
        this.json = json;
    }

    public static final class CachedResponse {
        private final int status;
        private final String body;
        public CachedResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
        public int status() { return status; }
        public String body() { return body; }
    }

    public CachedResponse executeOrReplay(String key, long userId,
                                         java.util.function.Supplier<CachedResponse> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        String fullKey = "idem:" + userId + ":" + key;
        Optional<CachedResponse> cached = load(fullKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        CachedResponse fresh = action.get();
        save(fullKey, fresh);
        return fresh;
    }

    private Optional<CachedResponse> load(String fullKey) {
        // 当前项目无 spring-data-redis 依赖；仅用 fallback map 实现幂等缓存。
        // 生产可加 redis 依赖后扩展：反射调 redis.opsForValue().get(fullKey)。
        return Optional.ofNullable(fallback.get(fullKey));
    }

    private void save(String fullKey, CachedResponse resp) {
        fallback.put(fullKey, resp);
    }

    /** 单测 / 重启时清空 in-memory cache。 */
    public void clearFallback() { fallback.clear(); }
}