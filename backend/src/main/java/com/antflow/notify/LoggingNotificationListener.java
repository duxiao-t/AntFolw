package com.antflow.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 默认通知监听器 — Sprint 3 C6：仅记日志（兜底）。
 * 生产应替换/增加 EmailNotificationListener / DingTalkNotificationListener 等。
 */
@Component
@Order(1000)
@Slf4j
public class LoggingNotificationListener implements NotificationListener {
    @Override
    public String name() { return "logging"; }

    @Override
    public void onEvent(NotificationEvent e) {
        log.info("[notify] type={} inst={} task={} user={} msg={}",
            e.getType(), e.getProcInstId(), e.getTaskId(), e.getUserId(), e.getMessage());
    }
}