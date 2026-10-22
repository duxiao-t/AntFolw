package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 自动化作业（延时 / 触发器 / 超时策略）的完成与推进。
 *
 * <p>方法体与拆分前逐字一致，四个易错处：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：入口 {@code ProcessEngine.completeAutomation} /
 *       {@code completeTaskTimeout} 已经开事务。作业状态、历史、后续节点必须同生共死——
 *       分两段提交的话，作业显示 SUCCEEDED 但流程没往前走，重试又会被"已成功"挡掉。</li>
 *   <li>阻塞型作业推进前必须复核"实例还停在这个节点"，否则实例已被别人驳回/撤回时
 *       会把流程推错分支；对不上就 CANCELLED，不报错（重复投递是常态）。</li>
 *   <li>并行分支内的阻塞节点要走 {@code advanceParallelBranch}，不能直接
 *       {@code resolveAndLand}：后者会跳过汇合网关，把另一条分支挂住。</li>
 *   <li>超时 AUTO_APPROVE 只对 riskLevel=LOW 生效，并且必须带
 *       {@code TIMEOUT_AUTO_APPROVE} 历史 action——审计要能分清"人工通过"与"系统代批"（A9）。
 *       代批本身仍回到 ProcessEngine（{@link FlowAdvancer#forceApproveAsSystem}），
 *       因为审批推进与字段回写都归引擎。</li>
 * </ol>
 */
final class AutomationCompleter {

    private final EngineSupport support;
    private final WorkflowJobMapper workflowJobMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final TaskMapper taskMapper;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;
    private final FlowAdvancer flow;

    AutomationCompleter(EngineSupport support, WorkflowJobMapper workflowJobMapper,
                        ProcessInstanceMapper processInstanceMapper, TaskMapper taskMapper,
                        Supplier<WorkflowRuntimeV2> runtimeV2, FlowAdvancer flow) {
        this.support = support;
        this.workflowJobMapper = workflowJobMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.taskMapper = taskMapper;
        this.runtimeV2 = runtimeV2;
        this.flow = flow;
    }

    boolean completeAutomation(Long jobId) {
        WorkflowJob initial = workflowJobMapper.selectById(jobId);
        if (initial == null) initial = workflowJobMapper.selectForUpdate(jobId);
        if (initial == null) return false;
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        WorkflowJob job = workflowJobMapper.selectForUpdate(jobId);
        if (job == null || !"RUNNING".equals(job.getStatus())) return false;
        if (instance == null || (Boolean.TRUE.equals(job.getBlocking())
            && !"RUNNING".equals(instance.getStatus()))) {
            job.setStatus("CANCELLED");
            job.setLockedAt(null);
            job.setLockedBy(null);
            workflowJobMapper.updateById(job);
            return false;
        }
        JsonNode root = null;
        if (Boolean.TRUE.equals(job.getBlocking())) {
            root = support.readProcessTree(instance);
            if (!Objects.equals(instance.getCurrentNodeId(), job.getNodeId())
                && !ProcessTreeNav.isInsideParallel(
                    root, instance.getCurrentNodeId(), job.getNodeId())) {
                job.setStatus("CANCELLED");
                job.setLastError("instance is no longer waiting at the automation node");
                job.setLockedAt(null);
                job.setLockedBy(null);
                workflowJobMapper.updateById(job);
                return false;
            }
        }

        job.setStatus("SUCCEEDED");
        job.setCompletedAt(OffsetDateTime.now());
        job.setLastError(null);
        job.setLockedAt(null);
        job.setLockedBy(null);
        workflowJobMapper.updateById(job);
        String action = "DELAY".equals(job.getJobType())
            ? "DELAY_COMPLETED" : "TRIGGER_SUCCEEDED";
        support.insertHistoryOnInstance(instance.getId(), job.getNodeId(), job.getNodeId(),
            action, null, "deliveryId=" + job.getDeliveryId());

        if (Boolean.TRUE.equals(job.getBlocking())) {
            JsonNode current = ProcessTreeNav.findById(root, job.getNodeId());
            if (current == null) {
                throw new BizException("BAD_FLOW", "automation node not found: " + job.getNodeId());
            }
            JsonNode formData = support.readFormData(instance.getFormDataId());
            ProcessTreeNav.ParallelParent parent =
                ProcessTreeNav.findParallelParent(root, current.path("id").asText());
            if (parent == null) {
                flow.resolveAndLand(root, instance, formData, instance.getStartedBy(), Map.of(),
                    current);
            } else {
                flow.advanceParallelBranch(root, instance, formData,
                    parent.parallelId(), parent.branchId(), current);
            }
        }
        return true;
    }

    /** Executes a persisted timeout action; already-handled tasks are a successful no-op. */
    boolean completeTaskTimeout(Long jobId) {
        WorkflowJob initial = workflowJobMapper.selectById(jobId);
        if (initial == null) return false;
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        WorkflowJob job = workflowJobMapper.selectForUpdate(jobId);
        if (job == null || !"TASK_TIMEOUT".equals(job.getJobType())
            || !"RUNNING".equals(job.getStatus())) return false;
        TaskEntity task = taskMapper.selectForUpdate(job.getTaskId());
        if (instance == null || !"RUNNING".equals(instance.getStatus())
            || task == null || !Objects.equals(task.getProcInstId(), instance.getId())
            || !"PENDING".equals(task.getStatus())) {
            finishTimeoutJob(job, "task already completed");
            return false;
        }
        JsonNode policy = support.readTreeOrEmpty(job.getPayload());
        String action = policy.path("action").asText("REMIND");
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        if (v2 == null) {
            finishTimeoutJob(job, "legacy task ignored");
            return false;
        }
        switch (action) {
            case "ESCALATE" -> v2.timeoutEscalate(job, task);
            case "AUTO_APPROVE" -> {
                if (!"LOW".equals(policy.path("riskLevel").asText())) {
                    v2.timeoutReminder(job, task);
                } else {
                    // 历史 action 单独标出来：审计时能区分"人工通过"与"系统超时代批"（A9）。
                    flow.forceApproveAsSystem(new CompleteCmd(task.getId(), "APPROVE",
                        "超时自动通过（系统代批）", null), task.getAssigneeId(),
                        "TIMEOUT_AUTO_APPROVE");
                }
            }
            default -> v2.timeoutReminder(job, task);
        }
        finishTimeoutJob(job, null);
        return true;
    }

    private void finishTimeoutJob(WorkflowJob job, String note) {
        job.setStatus("SUCCEEDED");
        job.setCompletedAt(OffsetDateTime.now());
        job.setLastError(note);
        job.setLockedAt(null);
        job.setLockedBy(null);
        workflowJobMapper.updateById(job);
        support.insertHistoryOnInstance(job.getProcInstId(), job.getNodeId(), job.getNodeId(),
            note == null ? "TASK_TIMEOUT" : "TASK_TIMEOUT_SKIPPED", null, note);
    }
}
