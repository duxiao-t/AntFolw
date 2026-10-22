package com.antflow.engine;

import com.antflow.authz.AuthorizationService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 管理动作：同意追回、管理员改派、管理员终止。
 *
 * <p>与审批推进分开的变化原因：这三条是**运维通道**（权限与状态检查口径不同），
 * 审批推进改的是业务规则。混在一起时，一次"运维口径调整"会碰到会签/或签代码，风险不对等。
 *
 * <p>四个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：三个入口在 ProcessEngine 上已经开事务。拆开后
 *       尤其是 {@code adminTerminate}，"运行时收口 + 实例置 REJECTED" 必须同生共死。</li>
 *   <li>{@code recallApproval} 不能用 {@link TaskLocker#lockPendingTask}：它处理的是**已通过**
 *       的任务，而 lockPendingTask 要求 PENDING。这里自己按"实例先锁、任务后锁"的顺序来。</li>
 *   <li>{@code adminTerminate} 即使 runtimeV2 为 null 也要继续把实例置为 REJECTED——
 *       旧实例没有 V2 收口，但"终止"这个动作本身仍然必须生效。</li>
 *   <li>错误码与文案是外部契约（BAD_RECALL_STATE / BAD_TASK_STATE / BAD_INSTANCE_STATE），
 *       不要"顺手统一"。</li>
 * </ol>
 */
final class AdminActions {

    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final AuthorizationService authorizationService;
    private final TaskLocker taskLocker;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    AdminActions(TaskMapper taskMapper, ProcessInstanceMapper processInstanceMapper,
                 AuthorizationService authorizationService, TaskLocker taskLocker,
                 Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.authorizationService = authorizationService;
        this.taskLocker = taskLocker;
        this.runtimeV2 = runtimeV2;
    }

    void recallApproval(long taskId, long operatorId) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        TaskEntity initial = taskMapper.selectById(taskId);
        if (initial == null) throw new BizException("NOT_FOUND", "task not found");
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        TaskEntity task = taskMapper.selectForUpdate(taskId);
        if (instance != null) authorizationService.requireConfirmedInstance(instance.getId());
        if (instance == null || task == null || !"RUNNING".equals(instance.getStatus())
            || !"APPROVED".equals(task.getStatus())
            || !Objects.equals(task.getApprovedBy(), operatorId)) {
            throw new BizException("BAD_RECALL_STATE", "仅能追回自己已同意且仍在流转的任务");
        }
        if (v2 == null) throw new BizException("BAD_RECALL_STATE", "旧流程不支持同意追回");
        v2.recallApproval(task, instance, operatorId);
    }

    void adminReassign(long taskId, long targetUserId, long operatorId, String reason) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        LockedTask locked = taskLocker.lockPendingTask(taskId);
        if (v2 == null) throw new BizException("BAD_TASK_STATE", "reassign requires V2 task");
        v2.adminReassign(locked.task(), targetUserId, operatorId, reason);
    }

    void adminTerminate(long instanceId, long operatorId, String reason) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        ProcessInstance instance = processInstanceMapper.selectForUpdate(instanceId);
        if (instance == null) throw new BizException("NOT_FOUND", "instance not found");
        if (!"RUNNING".equals(instance.getStatus())) {
            throw new BizException("BAD_INSTANCE_STATE", "only running instance can be terminated");
        }
        if (v2 != null) v2.terminate(instance, operatorId, reason);
        instance.setStatus("REJECTED");
        instance.setCurrentNodeId(null);
        instance.setCurrentNodeInstanceId(null);
        instance.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(instance);
    }
}
