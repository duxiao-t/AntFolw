package com.antflow.engine.dto;

/**
 * 审批操作请求。
 *
 * @param taskId        任务 id
 * @param action        APPROVE / REJECT / WITHDRAW
 * @param comment       审批意见
 * @param rejectToNodeId 驳回到指定节点的 id（仅 REJECT 有效，null=按 props.refuse 或 TO_END）
 */
public record CompleteCmd(Long taskId, String action, String comment,
                          String rejectToNodeId) {}