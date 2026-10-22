package com.antflow.mobile.workflow;

import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批记录 / 流转历史的投影口径。
 *
 * <p>为什么单独成类：这段规则最容易因为"看起来只是展示"被顺手改掉，但它同时是
 * 前端判断"谁在卡着流程"的唯一数据源。
 * <ul>
 *   <li>记录顺序 = 提交记录固定第一行 + {@code selectApprovalTasks} 的 {@code created_at, id} 顺序；</li>
 *   <li>抄送（CC）的"状态"取决于 {@code read_at} 而不是 task.status：
 *       未读是 {@code PROCESSING}、已读是 {@code APPROVED}，文案也随之切换；</li>
 *   <li>返工任务（task_type=REWORK 且 PENDING）算 {@code RETURNED}，不算"处理中"；</li>
 *   <li>{@code SKIP} 历史被过滤掉——它是引擎内部的空转，不该出现在用户看到的时间线上。</li>
 * </ul>
 * 这些口径同时被实例详情与任务详情复用，复制一份就一定会漂移。
 */
final class MobileApprovalRecordService {
    private final MobileWorkflowMapper workflowMapper;
    private final TaskHistoryMapper historyMapper;

    MobileApprovalRecordService(MobileWorkflowMapper workflowMapper,
                                TaskHistoryMapper historyMapper) {
        this.workflowMapper = workflowMapper;
        this.historyMapper = historyMapper;
    }

    List<MobileHistoryDto> history(Long instanceId) {
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
                .eq("proc_inst_id", instanceId)
                .ne("action", "SKIP")
                .orderByAsc("created_at")
                .orderByAsc("id"))
            .stream()
            .map(history -> new MobileHistoryDto(history.getId(), history.getFromNodeId(),
                history.getToNodeId(), history.getTaskId(), history.getAction(),
                history.getOperatorId(), history.getComment(), history.getCreatedAt()))
            .toList();
    }

    List<ApprovalRecordDto> approvalRecords(Long instanceId, JsonNode snapshot,
                                            String applicantName,
                                            String applicantEmployeeNo,
                                            String applicantDepartment,
                                            OffsetDateTime startedAt) {
        List<ApprovalRecordDto> records = new ArrayList<>();
        records.add(new ApprovalRecordDto(
            "submission", null, snapshot.path("id").asText("root"), "提交申请",
            "SUBMISSION", "ROOT", null, null, null, null, "SUBMITTED",
            applicantName == null ? "未记录" : applicantName, applicantEmployeeNo,
            applicantDepartment, null, startedAt, startedAt, 1));

        for (MobileWorkflowMapper.ApprovalRow task : workflowMapper.selectApprovalTasks(instanceId)) {
            String status = approvalRecordStatus(task);
            if (status == null) {
                continue;
            }
            JsonNode node = ProcessTreeNav.findById(snapshot, task.nodeId());
            ProcessTreeNav.ParallelParent parent = ProcessTreeNav.findParallelParent(
                snapshot, task.nodeId());
            String taskType = "REWORK".equals(task.taskType()) ? "REWORK"
                : "CC".equals(task.taskStatus()) ? "CC" : "APPROVAL";
            records.add(new ApprovalRecordDto(
                "task-" + task.taskId(), task.taskId(), task.nodeId(),
                "REWORK".equals(task.taskType()) ? "退回修改"
                    : MobileProjectionSupport.nodeName(snapshot, task.nodeId()),
                taskType, node == null ? taskType : node.path("type").asText(taskType),
                task.parallelId() != null ? task.parallelId()
                    : parent == null ? null : parent.parallelId(),
                task.branchId() != null ? task.branchId()
                    : parent == null ? null : parent.branchId(),
                task.operationKind(), task.sourceOperatorName(), status,
                task.operatorName(), task.employeeNo(), task.department(),
                approvalRecordComment(task), task.receivedAt(),
                "CC".equals(task.taskStatus()) ? task.readAt() : task.approvedAt(),
                task.roundNo()));
        }
        return records;
    }

    ApprovalSummaryDto approvalSummary(ProcessInstance instance,
                                       List<ApprovalRecordDto> records) {
        int processing = (int) records.stream()
            .filter(record -> "PROCESSING".equals(record.status())
                || "RETURNED".equals(record.status()))
            .count();
        int completed = records.size() - processing;
        return new ApprovalSummaryDto(records.size(), completed, processing,
            "APPROVED".equals(instance.getStatus()) && processing == 0);
    }

    private static String approvalRecordStatus(MobileWorkflowMapper.ApprovalRow task) {
        if ("CC".equals(task.taskStatus())) {
            return task.readAt() == null ? "PROCESSING" : "APPROVED";
        }
        if ("PENDING".equals(task.taskStatus())) {
            return "REWORK".equals(task.taskType()) ? "RETURNED" : "PROCESSING";
        }
        return switch (task.taskStatus()) {
            case "APPROVED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            case "RESUBMITTED" -> "RESUBMITTED";
            default -> null;
        };
    }

    private static String approvalRecordComment(MobileWorkflowMapper.ApprovalRow task) {
        if (!"CC".equals(task.taskStatus())) return task.comment();
        return task.readAt() == null ? "等待确认抄送内容。" : "已确认抄送内容。";
    }
}
