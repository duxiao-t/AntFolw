package com.antflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotency-Key 幂等服务：POST 请求带 {@code Idempotency-Key} header，
 * 重试时直接返回上次缓存的响应，防止重复提交。
 *
 * <p>当前用 in-memory ConcurrentHashMap 存储（单实例够用）。
 * 生产多实例部署时需升级为 Redis。
 */
@Service
public class IdempotencyService {

    private final ObjectMapper json;
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

    /** Direct cache lookup without triggering an action. Used by interceptors. */
    public CachedResponse peek(String fullKey) {
        return fallback.get(fullKey);
    }

    /** Direct cache write. Used by interceptors after the controller responds. */
    public void store(String fullKey, CachedResponse resp) {
        fallback.put(fullKey, resp);
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
