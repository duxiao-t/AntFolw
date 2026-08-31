package com.antflow.notify;

/**
 * 通知监听器接口 — Sprint 3 C6：
 * 业务模块（邮件 / IM / WebSocket）实现此接口并注册为 {@code @Component}，
 * 引擎发布的 {@link NotificationEvent} 会被所有 listener 接收。
 */
public interface NotificationListener {
    /** 监听器名（用于日志/调试） */
    default String name() { return getClass().getSimpleName(); }

    /** 是否对该事件感兴趣；默认 true */
    default boolean accepts(NotificationEvent e) { return true; }

    /** 处理事件；普通发布会隔离异常，outbox 可靠发布会用异常触发重试。 */
    void onEvent(NotificationEvent e);
}
