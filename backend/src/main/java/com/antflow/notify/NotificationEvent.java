package com.antflow.notify;

import org.springframework.context.ApplicationEvent;

/**
 * 流程通知事件 — Sprint 3 C6：
 * 引擎通过 Spring {@link ApplicationEventPublisher} 发布，
 * {@link NotificationListener} 实现按需订阅（邮件 / 钉钉 / WebSocket）。
 *
 * <p>事件类型：
 * <ul>
 *   <li>INSTANCE_STARTED — 实例发起</li>
 *   <li>TASK_ASSIGNED — 新建 PENDING 任务</li>
 *   <li>TASK_APPROVED / TASK_REJECTED / TASK_TRANSFERRED / TASK_DELEGATED / TASK_ADD_ASSIGNEE</li>
 *   <li>INSTANCE_APPROVED / INSTANCE_REJECTED / INSTANCE_WITHDRAWN</li>
 * </ul>
 */
public class NotificationEvent extends ApplicationEvent {
    private final String type;
    private final Long procInstId;
    private final Long taskId;
    private final Long userId;       // 接收人（task assignee / starter / delegator）
    private final String message;   // 人类可读消息

    public NotificationEvent(Object source, String type, Long procInstId,
                              Long taskId, Long userId, String message) {
        super(source);
        this.type = type;
        this.procInstId = procInstId;
        this.taskId = taskId;
        this.userId = userId;
        this.message = message;
    }

    public String getType() { return type; }
    public Long getProcInstId() { return procInstId; }
    public Long getTaskId() { return taskId; }
    public Long getUserId() { return userId; }
    public String getMessage() { return message; }
}