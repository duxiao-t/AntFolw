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
    void executeOrReplay_differentKeys_executeSeparately() {        var counter = new AtomicInteger(0);
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

    /**
     * UNKNOWN 结果不允许重放（回归 A2 第三条）。
     *
     * <p>处理链抛异常时业务可能已经生效；旧实现标 FAILED，而 tryClaim 允许 FAILED + 同 body
     * 重新抢占 → 客户端重试会再执行一次副作用。现在标 UNKNOWN：拿不到缓存响应，也抢不到锁，
     * 只能收到明确的"结果未知"冲突码。
     */
    @Test
    void unknownResultIsNotReplayable() {
        IdempotencyRecordMapper mapper = org.mockito.Mockito.mock(IdempotencyRecordMapper.class);
        byte[] body = "{\"formCode\":\"leave\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IdempotencyRecord row = new IdempotencyRecord();
        row.setId(7L);
        row.setStatus("UNKNOWN");
        row.setRequestHash(IdempotencyService.hash(body));

        org.mockito.Mockito.when(mapper.tryClaim(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(0);
        org.mockito.Mockito.when(mapper.find(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(row);

        IdempotencyService service = new IdempotencyService(new ObjectMapper(), mapper);
        IdempotencyService.Claim claim = service.claim(1L, "POST", "/api/instances/start", "k", body);

        assertThat(claim.replay()).isNull();
        assertThat(claim.claimed()).isFalse();
        assertThat(claim.conflict()).isEqualTo("IDEMPOTENCY_RESULT_UNKNOWN");
    }
}