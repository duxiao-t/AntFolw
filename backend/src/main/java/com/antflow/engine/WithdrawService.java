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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
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

    /**
     * 作废「待修改」申请：撤回后发起人放弃修改、要求从自己的系统里彻底清掉这张单。
     *
     * <p>这是**软终态**（{@code WITHDRAWN}），不做物理删除：{@code t_task_history} 有 append-only
     * 触发器、另有 7 张未级联子表，硬删会破坏审计。作废只允许在 {@code __rework__}（撤回后的待修改态）。
     *
     * <p>不加 {@code @Transactional}：入口 {@code ProcessEngine.discard} 已开事务，
     * "收口在办 + 表单/实例置终态"必须同生共死。
     */
    void discard(long instanceId, long operatorId) {
        WorkflowRuntimeV2 v2Runtime = runtimeV2.get();
        ProcessInstance pi = processInstanceMapper.selectForUpdate(instanceId);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        authorizationService.requireConfirmedInstance(instanceId);
        if (!Objects.equals(pi.getStartedBy(), operatorId)) {
            throw new AccessDeniedException("only starter can discard");
        }
        if (!"RUNNING".equals(pi.getStatus()) || !"__rework__".equals(pi.getCurrentNodeId())) {
            throw new BizException("BAD_STATE",
                "only a withdrawn submission awaiting revision can be discarded");
        }
        boolean v2 = v2Runtime != null && v2Runtime.active(pi);
        if (v2) {
            v2Runtime.discard(pi, operatorId);
        } else {
            List<TaskEntity> pending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId()).in("status", List.of("PENDING", "BLOCKED", "CC")));
            for (TaskEntity task : pending) {
                task.setStatus("CANCELLED");
                taskMapper.updateById(task);
            }
            support.insertHistoryOnInstance(pi.getId(), "__rework__", null, "DISCARD", operatorId, null);
        }
        FormData formData = formDataMapper.selectRuntimeById(pi.getFormDataId());
        if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
        formData.setStatus("WITHDRAWN");
        formDataMapper.updateById(formData);
        // 必须显式 set(null)：MyBatis-Plus 默认 NOT_NULL 策略会跳过 null 字段，
        // 用 updateById 清不掉 current_node_id（终态要把它置空，否则列表过滤与状态不一致）。
        processInstanceMapper.update(null, new LambdaUpdateWrapper<ProcessInstance>()
            .eq(ProcessInstance::getId, pi.getId())
            .set(ProcessInstance::getStatus, "WITHDRAWN")
            .set(ProcessInstance::getCurrentNodeId, null)
            .set(ProcessInstance::getCurrentNodeInstanceId, null)
            .set(ProcessInstance::getFinishedAt, OffsetDateTime.now()));
        if (!v2) {
            notifier.publish(new NotificationEvent(this, "INSTANCE_WITHDRAWN", pi.getId(), null,
                pi.getStartedBy(), "申请已作废"));
        }
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
