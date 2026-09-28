package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.notify.NotificationEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping({"/api/mobile/events", "/api/workflow/events"})
@RequiredArgsConstructor
public class MobileEventController {

    private static final Set<String> TASK_EVENTS = Set.of(
        "TASK_ASSIGNED", "TASK_RETURNED", "TASK_CANCELLED", "APPROVAL_INVALIDATED",
        "TASK_TIMEOUT_REMINDER", "CC_ASSIGNED");

    private final AuthorizationService authorizationService;
    private final Map<Long, CopyOnWriteArraySet<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authz.capability('" + PermissionCodes.WORKFLOW_TASK_READ + "')")
    public ResponseEntity<SseEmitter> events() {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        long userId = PrincipalHolder.current().orElseThrow().userId();
        SseEmitter emitter = new SseEmitter(0L);
        register(userId, emitter);
        send(userId, emitter, SseEmitter.event().name("ready").data("connected"));
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(emitter);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationEvent event) {
        if (!TASK_EVENTS.contains(event.getType()) || event.getUserId() == null) return;
        // 必须用允许 null 值的 Map：`CC_ASSIGNED` 没有任务 id（taskId 为 null），而 `Map.of`
        // 遇到 null 直接抛 NPE —— 异常会从 publishEvent 冒回 outbox 投递器，让这条抄送消息
        // 重试 10 次后进 DEAD（站内通知已写、SSE 却永远推不出去）。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.getType());
        payload.put("instanceId", event.getProcInstId());
        payload.put("taskId", event.getTaskId());
        sendToUser(event.getUserId(), SseEmitter.event().name("tasks-changed").data(payload));
    }

    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        emitters.forEach((userId, connections) ->
            connections.forEach(emitter -> send(userId, emitter, SseEmitter.event().comment("heartbeat"))));
    }

    void register(long userId, SseEmitter emitter) {
        // 注册与移除都走 compute 的同一个 key 原子操作：原来的 `computeIfAbsent(...).add(...)`
        // 与 remove 里的 `computeIfPresent`（删空集合时会把 key 摘掉）交错时，新连接可能被加进
        // 一个刚脱离 map 的集合里，从此收不到事件与心跳。
        emitters.compute(userId, (ignored, connections) -> {
            CopyOnWriteArraySet<SseEmitter> set =
                connections == null ? new CopyOnWriteArraySet<>() : connections;
            set.add(emitter);
            return set;
        });
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(ignored -> remove(userId, emitter));
    }

    void sendToUser(long userId, SseEmitter.SseEventBuilder event) {
        emitters.getOrDefault(userId, new CopyOnWriteArraySet<>())
            .forEach(emitter -> send(userId, emitter, event));
    }

    int connectionCount(long userId) {
        return emitters.getOrDefault(userId, new CopyOnWriteArraySet<>()).size();
    }

    private void send(long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ignored) {
            remove(userId, emitter);
            // A failed servlet write already starts async error completion.
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        emitters.computeIfPresent(userId, (ignored, connections) -> {
            connections.remove(emitter);
            return connections.isEmpty() ? null : connections;
        });
    }
}
