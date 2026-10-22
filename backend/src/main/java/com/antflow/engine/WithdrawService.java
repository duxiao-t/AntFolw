package com.antflow.engine;

import com.antflow.authz.AuthorizationService;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 发起人撤回：把当前审批轮次的在办任务整体收回，转成一张待修改的 REWORK 单。
 *
 * <p>方法体与拆分前逐字一致，四个易错处：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：调用方 {@code ProcessEngine.withdraw} 已经开事务，
 *       撤回要一次性完成"任务 SKIPPED + 节点/参与者收口 + 作业取消 + 表单需修改 + 历史"。</li>
 *   <li>{@code canWithdrawCurrentRound} 的"轮次边界"是**最近一张 RESUBMITTED 的 REWORK 任务**，
 *       不是时间戳：返工后重新提交过的单子允许再撤，但同一轮里只要有人处理过就不行。</li>
 *   <li>V2 实例必须走 {@code taskSkippedByWithdraw}/{@code withdrawToRework} 留痕，
 *       只把任务置 SKIPPED 会让审批人侧任务凭空消失（无历史、无通知），
 *       还会留下 ACTIVE 节点 + PENDING 参与者把"在办/超时"统计顶高（code-review A3）。</li>
 *   <li>先 {@code workflowJobMapper.cancelActive} 再 {@code withdrawToRework}：反过来的话，
 *       自动作业可能在节点已收口后又被投递一次。</li>
 * </ol>
 */
final class WithdrawService {

    private final EngineSupport support;
    private final ProcessInstanceMapper processInstanceMapper;
    private final TaskMapper taskMapper;
    private final WorkflowJobMapper workflowJobMapper;
    private final FormDataMapper formDataMapper;
    private final AuthorizationService authorizationService;
    private final NotificationPublisher notifier;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    WithdrawService(EngineSupport support, ProcessInstanceMapper processInstanceMapper,
                    TaskMapper taskMapper, WorkflowJobMapper workflowJobMapper,
                    FormDataMapper formDataMapper, AuthorizationService authorizationService,
                    NotificationPublisher notifier, Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.support = support;
        this.processInstanceMapper = processInstanceMapper;
        this.taskMapper = taskMapper;
        this.workflowJobMapper = workflowJobMapper;
        this.formDataMapper = formDataMapper;
        this.authorizationService = authorizationService;
        this.notifier = notifier;
        this.runtimeV2 = runtimeV2;
    }

    void withdraw(long instanceId, long operatorId) {
        WorkflowRuntimeV2 v2Runtime = runtimeV2.get();
        ProcessInstance pi = processInstanceMapper.selectForUpdate(instanceId);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        authorizationService.requireConfirmedInstance(instanceId);
        if (!Objects.equals(pi.getStartedBy(), operatorId)) {
            throw new AccessDeniedException("only starter can withdraw");
        }
        if (!"RUNNING".equals(pi.getStatus())) {
            throw new BizException("BAD_STATE", "instance not running");
        }
        if ("__rework__".equals(pi.getCurrentNodeId())) {
            throw new BizException("BAD_STATE", "instance already waiting for revision");
        }
        if (!canWithdrawCurrentRound(pi, operatorId)) {
            throw new BizException("ALREADY_ACTED",
                "cannot withdraw after a task has been acted on");
        }
        String previousNodeId = pi.getCurrentNodeId();
        boolean v2 = v2Runtime != null && v2Runtime.active(pi);
        List<TaskEntity> active = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).in("status", List.of("PENDING", "CC")));
        for (TaskEntity task : active) {
            task.setStatus("SKIPPED");
            taskMapper.updateById(task);
            // V2 实例必须留痕：原来只改状态，审批人侧任务凭空消失（无历史、无通知）。
            if (v2) v2Runtime.taskSkippedByWithdraw(pi, task);
        }
        workflowJobMapper.cancelActive(pi.getId());
        // 节点/参与者与任务一起收口：只置任务 SKIPPED 会留下 ACTIVE 节点 + PENDING 参与者，
        // 让"在办/超时"统计长期偏高（见 code-review A3）。
        if (v2) v2Runtime.withdrawToRework(pi);

        TaskEntity rework = EngineSupport.newReturnedTask(pi.getId(), "__rework__", "REWORK",
            "OR", pi.getStartedBy(), null, null);
        taskMapper.insert(rework);
        FormData formData = formDataMapper.selectRuntimeById(pi.getFormDataId());
        if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);

        pi.setStatus("RUNNING");
        pi.setCurrentNodeId("__rework__");
        pi.setFinishedAt(null);
        processInstanceMapper.updateById(pi);
        support.insertHistoryOnInstance(pi.getId(), previousNodeId, "__rework__",
            "WITHDRAW", operatorId, null);
        notifier.publish(new NotificationEvent(this, "TASK_RETURNED",
            pi.getId(), rework.getId(), pi.getStartedBy(), "申请已撤回，等待修改后重新提交"));
    }

    boolean canWithdraw(long instanceId, long operatorId) {
        ProcessInstance instance = processInstanceMapper.selectById(instanceId);
        return instance != null && canWithdrawCurrentRound(instance, operatorId);
    }

    private boolean canWithdrawCurrentRound(ProcessInstance instance, long operatorId) {
        if (!Objects.equals(instance.getStartedBy(), operatorId)
            || !"RUNNING".equals(instance.getStatus())
            || "__rework__".equals(instance.getCurrentNodeId())) {
            return false;
        }
        TaskEntity boundary = taskMapper.selectOne(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .eq("task_type", "REWORK")
            .eq("status", "RESUBMITTED")
            .orderByDesc("id").last("LIMIT 1"));
        QueryWrapper<TaskEntity> acted = new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .eq("task_type", "APPROVAL")
            .in("status", List.of("APPROVED", "REJECTED"))
            .isNotNull("approved_by");
        if (boundary != null) acted.gt("id", boundary.getId());
        Long count = taskMapper.selectCount(acted);
        return count == null || count == 0;
    }
}
