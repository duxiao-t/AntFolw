package com.antflow.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知发布器 — Sprint 3 C6：引擎通过此 service 发布事件。
 *
 * <p>事件用 Spring {@link ApplicationEventPublisher} 发到容器，事务提交后
 * 由监听器异步处理（{@code @TransactionalEventListener(phase = AFTER_COMMIT)}）；
 * 这里额外保留同步 listener 入口以便单元测试可控验证。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisher {

    private final ApplicationEventPublisher publisher;
    private final List<NotificationListener> syncListeners;

    /** Spring 事件总线（监听器通过 @EventListener 或 @TransactionalEventListener 接） */
    public void publish(NotificationEvent e) {
        publisher.publishEvent(e);
        // 同步 listener（用于 log 兜底 / 单测）
        for (NotificationListener l : syncListeners) {
            try {
                if (l.accepts(e)) l.onEvent(e);
            } catch (Exception ex) {
                log.warn("Notification listener {} failed: {}", l.name(), ex.toString());
            }
        }
    }

    /** Outbox delivery path: listener failure must remain visible so it can be retried. */
    public void publishReliable(NotificationEvent e) {
        publisher.publishEvent(e);
        for (NotificationListener listener : syncListeners) {
            if (listener.accepts(e)) listener.onEvent(e);
        }
    }
}
