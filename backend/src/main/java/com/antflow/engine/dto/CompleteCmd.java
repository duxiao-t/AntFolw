package com.antflow.engine.dto;

/**
 * 审批操作请求。
 *
 * @param taskId        任务 id
 * @param action        APPROVE / REJECT / WITHDRAW
 * @param comment       审批意见
 * @param rejectToNodeId 驳回到指定节点的 id（仅 REJECT 有效，null=按 props.refuse 或 TO_END）
 * @param data          审批节点可编辑字段的新值（仅 APPROVE 有效；null=不修改表单数据）
 */
public record CompleteCmd(Long taskId, String action, String comment,
                          String rejectToNodeId, Object data) {
    public CompleteCmd(Long taskId, String action, String comment, String rejectToNodeId) {
        this(taskId, action, comment, rejectToNodeId, null);
    }
}
