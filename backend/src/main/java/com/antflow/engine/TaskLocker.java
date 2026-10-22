package com.antflow.engine;

import com.antflow.authz.AuthorizationService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 任务加锁与任务级前置动作：把一条 PENDING 任务和它的实例一起锁住，再交给编排逻辑。
 *
 * <p>为什么单独一类：**加锁顺序**是本模块唯一的并发契约（先实例、再任务，且任务必须复核），
 * 它被审批、驳回、返工重提、管理员改派四处复用。散在编排里时，任何一处漏掉
 * {@code requireConfirmedInstance} 或把顺序写反，都只会在并发下偶发。集中一处才有唯一对照物。
 *
 * <p>三个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：调用方已经是事务方法。这里只是"取锁 + 校验"，
 *       自己开事务等于把锁释放在校验之后、业务之前。</li>
 *   <li>顺序不能换：先 {@code selectForUpdate(instance)} 再 {@code selectForUpdate(task)}。
 *       反过来会与"撤回（锁实例）→ 完成任务（锁任务）"的路径形成死锁。</li>
 *   <li>拿到锁之后**必须复核**：任务仍 PENDING、仍属于该实例、实例仍 RUNNING。
 *       进锁前读到的状态一律不可信（另一个事务可能刚提交）。</li>
 * </ol>
 */
final class TaskLocker {

    private final EngineSupport support;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final AuthorizationService authorizationService;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    TaskLocker(EngineSupport support, TaskMapper taskMapper,
               ProcessInstanceMapper processInstanceMapper,
               AuthorizationService authorizationService,
               Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.support = support;
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.authorizationService = authorizationService;
        this.runtimeV2 = runtimeV2;
    }

    LockedTask lockPendingTask(long taskId) {
        TaskEntity initial = taskMapper.selectById(taskId);
        if (initial == null) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        if (instance == null) {
            throw new BizException("NOT_FOUND", "instance not found");
        }
        authorizationService.requireConfirmedInstance(instance.getId());
        TaskEntity current = taskMapper.selectForUpdate(taskId);
        if (current == null || !Objects.equals(current.getProcInstId(), instance.getId())
            || !"PENDING".equals(current.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        if (!"RUNNING".equals(instance.getStatus())) {
            throw new BizException("BAD_INSTANCE_STATE", "终态流程不能继续处理任务");
        }
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        if (v2 != null) v2.requireActiveTask(current, instance);
        return new LockedTask(current, instance);
    }

    /** 乐观并发：updateById 必须命中 1 行，否则说明任务已被别人改过。 */
    void updateTaskOrConflict(TaskEntity task) {
        support.updateTaskOrConflict(task);
    }

    /**
     * 委托任务（delegatedFrom != null 且非加签）审批时，原任务若仍为 PENDING，
     * 则由委托任务替代原任务完成，避免 AND 模式把“同一审批人的镜像任务”算成两个人。
     */
    void skipDelegatedParentIfNeeded(TaskEntity task, long operatorId) {
        if (task.getDelegatedFrom() == null || Boolean.TRUE.equals(task.getIsAdditional())
                || task.getParentTaskId() == null) {
            return;
        }
        TaskEntity parent = taskMapper.selectById(task.getParentTaskId());
        if (parent == null || !"PENDING".equals(parent.getStatus())
                || !Objects.equals(parent.getNodeId(), task.getNodeId())) {
            return;
        }
        parent.setStatus("SKIPPED");
        parent.setComment("由委托任务 #" + task.getId() + " 处理");
        taskMapper.updateById(parent);
        support.insertHistoryOnInstance(parent.getProcInstId(), parent.getNodeId(), parent.getNodeId(),
            "SKIP", operatorId, "delegated task acted");
    }
}

/**
 * 已锁定的任务 + 它所属的实例。单独成文件级类型（而不是嵌套在某个类里），
 * 因为它同时被审批、驳回、返工、管理动作四处传递；嵌套会让某一方"借用"另一方的内部类型。
 */
record LockedTask(TaskEntity task, ProcessInstance instance) { }
