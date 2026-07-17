package com.antflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Sprint 3 C10 Idempotency-Key 单测（无 Redis 退化到 in-memory） */
class IdempotencyServiceTest {

    private IdempotencyService svc;

    @BeforeEach void setup() {
        svc = new IdempotencyService(new ObjectMapper());
        svc.clearFallback();
    }

    @Test
    void executeOrReplay_cachesByKey() {
        var counter = new AtomicInteger(0);
        java.util.function.Supplier<IdempotencyService.CachedResponse> action = () -> {
            int n = counter.incrementAndGet();
            return new IdempotencyService.CachedResponse(200, "{\"n\":" + n + "}");
        };
        var first = svc.executeOrReplay("abc123", 42L, action);
        var second = svc.executeOrReplay("abc123", 42L, action);
        var third = svc.executeOrReplay("abc123", 42L, action);

        assertThat(first.body()).contains("\"n\":1");
        assertThat(second.body()).contains("\"n\":1");
        assertThat(third.body()).contains("\"n\":1");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void executeOrReplay_differentKeys_executeSeparately() {
        var counter = new AtomicInteger(0);
        java.util.function.Supplier<IdempotencyService.CachedResponse> action = () ->
            new IdempotencyService.CachedResponse(200,
                "{\"n\":" + counter.incrementAndGet() + "}");
        svc.executeOrReplay("key1", 42L, action);
        svc.executeOrReplay("key2", 42L, action);
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void executeOrReplay_differentUsers_sameKey_executeSeparately() {
        var counter = new AtomicInteger(0);
        java.util.function.Supplier<IdempotencyService.CachedResponse> action = () ->
            new IdempotencyService.CachedResponse(200,
                "{\"n\":" + counter.incrementAndGet() + "}");
        svc.executeOrReplay("same-key", 42L, action);
        svc.executeOrReplay("same-key", 99L, action);
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void executeOrReplay_emptyKey_alwaysExecutes() {
        var counter = new AtomicInteger(0);
        java.util.function.Supplier<IdempotencyService.CachedResponse> action = () ->
            new IdempotencyService.CachedResponse(200,
                "{\"n\":" + counter.incrementAndGet() + "}");
        svc.executeOrReplay(null, 42L, action);
        svc.executeOrReplay("", 42L, action);
        svc.executeOrReplay("   ", 42L, action);
        assertThat(counter.get()).isEqualTo(3);
    }
}