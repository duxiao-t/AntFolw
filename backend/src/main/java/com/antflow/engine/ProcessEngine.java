package com.antflow.engine;

import com.antflow.automation.WorkflowJobMapper;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.common.FormalNumberService;
import com.antflow.common.BusinessNumberService;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.handler.NodeHandler;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 钉钉式流程树的运行时引擎（Task 7）。
 *
 * <p>流程由 {@link ProcessDefinition#getProcess()}（JSONB 字符串）持有：
 * 每个节点 {@code {id, type, props, children, branchs?}}；业务节点用单个
 * {@code children} 指向唯一后继；{@code CONDITIONS} 有
 * {@code branchs[]}+{@code children}（合流后续）。
 *
 * <p>引擎入口：
 * <ul>
 *   <li>{@link #start(StartCmd, long)} — 建 FormData(SUBMITTED) + ProcessInstance(RUNNING)，
 *       从 ROOT 出发首次 {@code resolveAndLand}。</li>
 *   <li>{@link #approve(CompleteCmd, long)} — 标记 PENDING 任务 APPROVED，按节点 mode
 *       （OR→跳兄弟+推进；AND→等全员再推进）处理后继续 {@code resolveAndLand}。</li>
 *   <li>{@link #reject(CompleteCmd, long)} — 标记当前任务 REJECTED，并退回直接上一级。</li>
 *   <li>{@link #withdraw(long, long)} — 发起人在当前审批轮次被人工处理前撤回修改。</li>
 * </ul>
 */
@Service
public class ProcessEngine implements FlowAdvancer {

    // -----------------------------------------------------------------------
    // 协作者。14 个构造参数只用于**装配**，本类不再直接读它们：所有业务动作都已下沉到
    // 下面的协作者，保留一份同名字段只会让"到底谁在写库"变得模糊。
    // -----------------------------------------------------------------------
    /** 返工单重新提交。 */
    private final ReworkService reworkService;
    /** 发起人撤回（含轮次边界判定）。 */
    private final WithdrawService withdrawService;
    /** 自动化作业完成（延时 / 触发器 / 超时策略）。 */
    private final AutomationCompleter automationCompleter;
    /** 节点落地（含并行分支推进）。 */
    private final NodeLander nodeLander;
    /** 发起：建表单 + 建实例 + 落首节点。 */
    private final ProcessStarter starter;
    /** 任务加锁与任务级前置动作。 */
    private final TaskLocker taskLocker;
    /** 管理动作（追回 / 改派 / 终止）。 */
    private final AdminActions adminActions;
    /** 审批 / 驳回的编排（锁任务 → 校验 → 分流 → 结算或落地）。 */
    private final ApprovalOrchestrator approvalOrchestrator;

    @Autowired
    private WorkflowRuntimeV2 runtimeV2;
    @Autowired(required = false)
    private BusinessNumberService businessNumbers;

    /**
     * 延迟读取：runtimeV2 是字段注入，构造期还是 null；单测也会在构造后用
     * ReflectionTestUtils 替换它。用 Supplier 让每次调用都读到当前值。
     */
    private final java.util.function.Supplier<WorkflowRuntimeV2> runtimeV2Ref = () -> runtimeV2;
    /** 同上：发起编号的可选 bean 也是字段注入。 */
    private final java.util.function.Supplier<BusinessNumberService> businessNumbersRef =
        () -> businessNumbers;

    /**
     * 显式构造器（替代 {@code @RequiredArgsConstructor}）：协作者要在这里被创建，
     * 而它们又需要本实例作为 {@link FlowAdvancer} 回调口。
     *
     * <p>构造期就传出 {@code this} 是刻意的，也是安全的：协作者只是把引用存下来，
     * 真正的回调发生在本实例构造完成、进入请求处理之后。
     */
    public ProcessEngine(FormDefinitionService formDefinitionService,
                         FormDataMapper formDataMapper,
                         ProcessDefinitionService processDefinitionService,
                         TaskMapper taskMapper,
                         ProcessInstanceMapper processInstanceMapper,
                         TaskMapperExt taskMapperExt,
                         TaskHistoryMapper historyMapper,
                         List<NodeHandler> nodeHandlers,
                         NotificationPublisher notifier,
                         ObjectMapper json,
                         FormalNumberService formalNumberService,
                         WorkflowJobMapper workflowJobMapper,
                         AuthorizationService authorizationService,
                         com.antflow.attachment.MediaSubmissionValidator mediaValidator) {
        // 装配顺序 = 依赖顺序：support 是所有协作者的读写底座；结算/落地不依赖编排，
        // 编排最后建（它要拿到前面这些实例）。中间的字段都用局部变量，避免本类顺手读到它们。
        EngineSupport support = new EngineSupport(json, taskMapper, formDataMapper, historyMapper,
            formDefinitionService, processDefinitionService, runtimeV2Ref);
        LegacyDecisionSettler legacySettler = new LegacyDecisionSettler(support, taskMapper,
            processInstanceMapper, formDataMapper, notifier, this);
        V2DecisionSettler v2Settler = new V2DecisionSettler(support, taskMapper,
            processInstanceMapper, formDataMapper, runtimeV2Ref, this);
        this.reworkService = new ReworkService(support, taskMapper, processInstanceMapper,
            formDataMapper, formDefinitionService, mediaValidator, runtimeV2Ref, this);
        this.withdrawService = new WithdrawService(support, processInstanceMapper, taskMapper,
            workflowJobMapper, formDataMapper, authorizationService, notifier, runtimeV2Ref);
        this.automationCompleter = new AutomationCompleter(support, workflowJobMapper,
            processInstanceMapper, taskMapper, runtimeV2Ref, this);
        FieldEditApplier fieldEditApplier = new FieldEditApplier(support, formDataMapper,
            formDefinitionService, processInstanceMapper, mediaValidator, json, runtimeV2Ref);
        this.nodeLander = new NodeLander(support, taskMapper, processInstanceMapper,
            workflowJobMapper, nodeHandlers, notifier, runtimeV2Ref);
        this.starter = new ProcessStarter(support, formDefinitionService, formDataMapper,
            processDefinitionService, processInstanceMapper, formalNumberService,
            authorizationService, mediaValidator, notifier, nodeLander, runtimeV2Ref,
            businessNumbersRef);
        this.taskLocker = new TaskLocker(support, taskMapper, processInstanceMapper,
            authorizationService, runtimeV2Ref);
        this.adminActions = new AdminActions(taskMapper, processInstanceMapper,
            authorizationService, taskLocker, runtimeV2Ref);
        this.approvalOrchestrator = new ApprovalOrchestrator(support, taskLocker,
            fieldEditApplier, nodeLander, legacySettler, v2Settler, taskMapper, runtimeV2Ref);
    }

    /**
     * 发起流程。事务边界留在这里（入口），实现已下沉到 {@link ProcessStarter}。
     *
     * <p>不要把这个 {@code @Transactional} 删掉：拆开后实现类有 4 次写库（表单、实例、首任务、历史），
     * 没有外层事务就会各自提交。
     */
    @Transactional
    public Map<String, Object> start(StartCmd cmd, long userId) {
        return starter.start(cmd, userId);
    }

    @Transactional
    public void approve(CompleteCmd cmd, long operatorId) {
        approvalOrchestrator.approve(cmd, operatorId, false, null);
    }

    @Transactional
    public void forceApprove(CompleteCmd cmd, long operatorId) {
        approvalOrchestrator.approve(cmd, operatorId, true, null);
    }

    /**
     * 系统代批入口（超时自动通过），供 {@link AutomationCompleter} 回调。
     *
     * <p>刻意不加 {@code @Transactional}：它总是在 AutomationCompleter 的事务里被调用，
     * 再进一次事务代理没有任何收益，只会让"这段到底开没开新事务"变得需要推理。
     *
     * <p>**不要从控制器/其他服务直接调用**：没有外层事务时悲观锁与多表写入会各自提交。
     * 外部要走 {@link #forceApprove}。
     */
    @Override
    public void forceApproveAsSystem(CompleteCmd cmd, long operatorId, String historyAction) {
        approvalOrchestrator.approve(cmd, operatorId, true, historyAction);
    }

    /** 同意追回；事务边界留在这里，实现见 {@link AdminActions}。 */
    @Transactional
    public void recallApproval(long taskId, long operatorId) {
        adminActions.recallApproval(taskId, operatorId);
    }

    @Transactional
    public void adminReassign(long taskId, long targetUserId, long operatorId, String reason) {
        adminActions.adminReassign(taskId, targetUserId, operatorId, reason);
    }

    @Transactional
    public void adminTerminate(long instanceId, long operatorId, String reason) {
        adminActions.adminTerminate(instanceId, operatorId, reason);
    }

    /**
     * 并行分支推进留在本类作为门面：{@link V2DecisionSettler} / {@link AutomationCompleter} 通过
     * {@link FlowAdvancer} 回调这里。实现已下沉到 {@link NodeLander}，不直接暴露它，
     * 是为了让"结算类只认识一个回调口"这条约束不因拆分而破。
     */
    @Override
    public void advanceParallelBranch(JsonNode root, ProcessInstance instance,
                                      JsonNode formData, String parallelId,
                                      String branchId, JsonNode completedNode) {
        nodeLander.advanceParallelBranch(root, instance, formData, parallelId, branchId,
            completedNode);
    }

    @Transactional
    public void reject(CompleteCmd cmd, long operatorId) {
        approvalOrchestrator.reject(cmd, operatorId, false);
    }

    @Transactional
    public void forceReject(CompleteCmd cmd, long operatorId) {
        approvalOrchestrator.reject(cmd, operatorId, true);
    }

    @Transactional
    public List<Long> resubmitRework(long taskId, long operatorId) {
        LockedTask locked = taskLocker.lockPendingTask(taskId);
        TaskEntity task = locked.task();
        if (!"REWORK".equals(task.getTaskType())) {
            throw new BizException("TASK_NOT_PENDING", "Rework task not pending");
        }
        if (!Objects.equals(task.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }
        return reworkService.resubmit(task, locked.instance(), operatorId);
    }

    @Transactional
    public void withdraw(long instanceId, long operatorId) {
        withdrawService.withdraw(instanceId, operatorId);
    }

    /** 发起人作废「待修改」申请（软终态 WITHDRAWN）。 */
    @Transactional
    public void discard(long instanceId, long operatorId) {
        withdrawService.discard(instanceId, operatorId);
    }

    public boolean canWithdraw(long instanceId, long operatorId) {
        return withdrawService.canWithdraw(instanceId, operatorId);
    }

    /**
     * Completes a claimed automation job and, for blocking nodes, advances the
     * process in the same transaction. Repeated completion calls are no-ops.
     */
    @Transactional
    public boolean completeAutomation(Long jobId) {
        return automationCompleter.completeAutomation(jobId);
    }

    /** Executes a persisted timeout action; already-handled tasks are a successful no-op. */
    @Transactional
    public boolean completeTaskTimeout(Long jobId) {
        return automationCompleter.completeTaskTimeout(jobId);
    }


    /**
     * 节点落地已下沉到 {@link NodeLander}；这里保留 {@link FlowAdvancer} 的三个门面方法，
     * 因为结算类/自动化类只认这个回调口（见 {@link FlowAdvancer} 的注释）。
     */
    @Override
    public List<Long> resolveAndLand(JsonNode root, ProcessInstance pi,
                                     JsonNode formData, long starterId,
                                     Map<String, List<Long>> selfSelected,
                                     JsonNode fromNode) {
        return nodeLander.resolveAndLand(root, pi, formData, starterId, selfSelected, fromNode);
    }

    @Override
    public List<Long> resolveAndLandFromNode(JsonNode root, ProcessInstance pi,
                                             JsonNode formData, long starterId,
                                             Map<String, List<Long>> selfSelected,
                                             JsonNode targetNode) {
        return nodeLander.resolveAndLandFromNode(root, pi, formData, starterId, selfSelected,
            targetNode);
    }
}
