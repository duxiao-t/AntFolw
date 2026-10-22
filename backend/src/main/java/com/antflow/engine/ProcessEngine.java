package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.common.FormalNumberService;
import com.antflow.common.BusinessNumberService;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.handler.NodeContext;
import com.antflow.engine.handler.NodeHandler;
import com.antflow.engine.handler.NodeOutcome;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessDefinitionService processDefinitionService;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final TaskMapperExt taskMapperExt;
    private final TaskHistoryMapper historyMapper;
    private final List<NodeHandler> nodeHandlers;
    private final NotificationPublisher notifier;
    private final ObjectMapper json;
    private final FormalNumberService formalNumberService;
    private final WorkflowJobMapper workflowJobMapper;
    private final AuthorizationService authorizationService;
    private final com.antflow.attachment.MediaSubmissionValidator mediaValidator;

    /** 协作者共用的读写底座；与上面这些依赖是同一批实例（单测里的 mock 也就只有一份）。 */
    private final EngineSupport support;
    /** V1 驳回结算（历史实例兼容层）。 */
    private final LegacyDecisionSettler legacySettler;
    /** V2 结算（当前唯一写入运行时）。 */
    private final V2DecisionSettler v2Settler;
    /** 返工单重新提交。 */
    private final ReworkService reworkService;
    /** 发起人撤回（含轮次边界判定）。 */
    private final WithdrawService withdrawService;
    /** 自动化作业完成（延时 / 触发器 / 超时策略）。 */
    private final AutomationCompleter automationCompleter;

    @Autowired
    private WorkflowRuntimeV2 runtimeV2;
    @Autowired(required = false)
    private BusinessNumberService businessNumbers;

    /**
     * 延迟读取：runtimeV2 是字段注入，构造期还是 null；单测也会在构造后用
     * ReflectionTestUtils 替换它。用 Supplier 让每次调用都读到当前值。
     */
    private final java.util.function.Supplier<WorkflowRuntimeV2> runtimeV2Ref = () -> runtimeV2;

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
        this.formDefinitionService = formDefinitionService;
        this.formDataMapper = formDataMapper;
        this.processDefinitionService = processDefinitionService;
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.taskMapperExt = taskMapperExt;
        this.historyMapper = historyMapper;
        this.nodeHandlers = nodeHandlers;
        this.notifier = notifier;
        this.json = json;
        this.formalNumberService = formalNumberService;
        this.workflowJobMapper = workflowJobMapper;
        this.authorizationService = authorizationService;
        this.mediaValidator = mediaValidator;
        this.support = new EngineSupport(json, taskMapper, formDataMapper, historyMapper,
            formDefinitionService, processDefinitionService, runtimeV2Ref);
        this.legacySettler = new LegacyDecisionSettler(support, taskMapper,
            processInstanceMapper, formDataMapper, notifier, this);
        this.v2Settler = new V2DecisionSettler(support, taskMapper, processInstanceMapper,
            formDataMapper, runtimeV2Ref, this);
        this.reworkService = new ReworkService(support, taskMapper, processInstanceMapper,
            formDataMapper, formDefinitionService, mediaValidator, runtimeV2Ref, this);
        this.withdrawService = new WithdrawService(support, processInstanceMapper, taskMapper,
            workflowJobMapper, formDataMapper, authorizationService, notifier, runtimeV2Ref);
        this.automationCompleter = new AutomationCompleter(support, workflowJobMapper,
            processInstanceMapper, taskMapper, runtimeV2Ref, this);
    }

    @Transactional
    public Map<String, Object> start(StartCmd cmd, long userId) {
        // P4a：归属由**调用方**解析好（它才分得清"用户显式选的"与"从草稿继承的"），
        // 引擎只做 fail-closed 检查——绝不让一条无归属的记录静默落库。
        Long companyId = cmd.companyId();
        if (companyId == null) {
            throw new BizException("BAD_COMPANY", "发起时必须确定公司归属");
        }
        if (runtimeV2 == null) {
            throw new BizException("RUNTIME_REQUIRED", "发起流程需要 V2 运行时");
        }
        FormDefinition fd = formDefinitionService.getByCode(cmd.formCode());
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + cmd.formCode());
        }
        // 表单使用授权：未获得 t_form_resource_grant 授权的用户不能发起该表单的流程。
        authorizationService.requireFormUse(fd.getId(), companyId);
        ProcessDefinition pd = processDefinitionService.latestPublishedForForm(fd.getId());
        if (pd == null) {
            throw new BizException("NO_FLOW", "No published process for form " + cmd.formCode());
        }
        processDefinitionService.requireNodeFallbackPolicy(pd.getProcess());
        var visibleData = formDefinitionService.canonicalizeStarterSubmission(
            fd.getSchema(), cmd.data(), pd.getProcess());

        FormData fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setBusinessNo(businessNumbers == null
            ? formalNumberService.businessNo() : businessNumbers.next(fd, visibleData));
        fd2.setData(writeJson(visibleData));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        fd2.setCompanyId(companyId);
        fd2.setCompanyInferred(false);
        formDataMapper.insert(fd2);
        mediaValidator.validate(fd2.getId());

        ProcessInstance pi = new ProcessInstance();
        pi.setProcDefId(pd.getId());
        pi.setProcessDefVersion(pd.getVersion());
        String normalizedProcess = processDefinitionService.normalizeConditionValues(
            pd.getProcess(), fd.getSchema());
        pi.setProcessSnapshot(normalizedProcess == null ? pd.getProcess() : normalizedProcess);
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        PrincipalHolder.current()
            .filter(principal -> principal.userId() == userId)
            .ifPresent(principal -> pi.setStartedDeptId(principal.departmentId()));
        pi.setStartedAt(OffsetDateTime.now());
        // 与 fd2 同一个值（同一次解析、同一个事务）。
        pi.setCompanyId(companyId);
        pi.setCompanyInferred(false);
        WorkflowRuntimeV2.StartState state = runtimeV2.prepareStart(fd2, pd, userId);
        pi.setEngineVersion(2);
        pi.setRoundNo(1);
        pi.setProcessDefinitionVersionId(state.processVersionId());
        pi.setCurrentFormRevisionId(state.revisionId());
        processInstanceMapper.insert(pi);

        // 引擎后续一律走快照树，而非 pd.getProcess()
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode formData = readTreeOrEmpty(fd2.getData());
        Map<String, List<Long>> selfSelected =
            cmd.selfSelected() == null ? Map.of() : cmd.selfSelected();

        List<Long> firstTasks = resolveAndLand(root, pi, formData, userId, selfSelected, root);
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            runtimeV2.outbox(pi.getId(), userId, "INSTANCE_STARTED", null);
        } else {
            notifier.publish(new NotificationEvent(this, "INSTANCE_STARTED",
                pi.getId(), null, userId, "流程发起 #" + pi.getId()));
        }
        return Map.of(
            "instanceId", pi.getId(),
            "formDataId", fd2.getId(),
            "businessNo", fd2.getBusinessNo(),
            "firstTaskIds", firstTasks
        );
    }

    @Transactional
    public void approve(CompleteCmd cmd, long operatorId) {
        approveInternal(cmd, operatorId, false);
    }

    @Transactional
    public void forceApprove(CompleteCmd cmd, long operatorId) {
        approveInternal(cmd, operatorId, true);
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
        approveInternal(cmd, operatorId, true, historyAction);
    }

    @Transactional
    public void recallApproval(long taskId, long operatorId) {
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
        if (runtimeV2 == null) throw new BizException("BAD_RECALL_STATE", "旧流程不支持同意追回");
        runtimeV2.recallApproval(task, instance, operatorId);
    }

    @Transactional
    public void adminReassign(long taskId, long targetUserId, long operatorId, String reason) {
        LockedTask locked = lockPendingTask(taskId);
        if (runtimeV2 == null) throw new BizException("BAD_TASK_STATE", "reassign requires V2 task");
        runtimeV2.adminReassign(locked.task(), targetUserId, operatorId, reason);
    }

    @Transactional
    public void adminTerminate(long instanceId, long operatorId, String reason) {
        ProcessInstance instance = processInstanceMapper.selectForUpdate(instanceId);
        if (instance == null) throw new BizException("NOT_FOUND", "instance not found");
        if (!"RUNNING".equals(instance.getStatus())) {
            throw new BizException("BAD_INSTANCE_STATE", "only running instance can be terminated");
        }
        if (runtimeV2 != null) runtimeV2.terminate(instance, operatorId, reason);
        instance.setStatus("REJECTED");
        instance.setCurrentNodeId(null);
        instance.setCurrentNodeInstanceId(null);
        instance.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(instance);
    }

    private void approveInternal(CompleteCmd cmd, long operatorId, boolean force) {
        approveInternal(cmd, operatorId, force, null);
    }

    /**
     * @param historyAction 非空时覆盖历史 action（用于把"系统代批"与人工通过区分开）。
     *                      审计口径见 code-review A9：approved_by 仍是审批人本人，
     *                      但历史里能明确看到这是超时自动通过、不是人工点的。
     */
    private void approveInternal(CompleteCmd cmd, long operatorId, boolean force,
                                 String historyAction) {
        LockedTask locked = lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task must be resubmitted from the form");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }
        skipDelegatedParentIfNeeded(t, operatorId);

        // 永远走快照，不依赖 pd.getProcess()（避免流程改版后已发起的实例跑飞）
        JsonNode root = readProcessTree(pi);
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "approval node not in tree: " + t.getNodeId());
        }
        if (cmd.data() != null) {
            applyNodeFieldEdits(pi, cur, cmd.data(), operatorId);
        }

        t.setStatus("APPROVED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            t.setActionFormRevisionId(pi.getCurrentFormRevisionId());
        }
        updateTaskOrConflict(t);
        insertHistory(t, null, t.getNodeId(),
            historyAction != null ? historyAction : force ? "FORCE_APPROVE" : "APPROVE",
            operatorId, cmd.comment());

        if (runtimeV2 != null && runtimeV2.active(pi) && t.getNodeInstanceId() != null) {
            if ("ADD_BEFORE".equals(t.getOperationKind())) {
                runtimeV2.completeBeforeSign(t);
                return;
            }
            if (runtimeV2.activateAfterSign(t)) return;
            WorkflowRuntimeV2.Decision decision = runtimeV2.approve(t, cur);
            if (!decision.newTaskIds().isEmpty()) {
                notifyAssigned(pi.getId(), decision.newTaskIds());
            }
            settleV2Decision(decision, cmd, operatorId, force, t, pi, root, cur);
            return;
        }

        String mode = WorkflowRuntimeV2.mode(cur);
        if ("ALL".equals(mode) || "RATIO".equals(mode)) {
            List<TaskEntity> votes = legacySettler.legacyNodeVotes(t);
            if (votes.stream().anyMatch(task -> "PENDING".equals(task.getStatus()))) return;
            long approved = votes.stream()
                .filter(task -> "APPROVED".equals(task.getStatus())).count();
            boolean passed = "ALL".equals(mode)
                ? approved == votes.size()
                : approved >= LegacyDecisionSettler.requiredApprovals(votes.size(), cur);
            if (!passed) {
                TaskEntity rejection = votes.stream()
                    .filter(task -> "REJECTED".equals(task.getStatus()))
                    .findFirst().orElse(t);
                legacySettler.settleLegacyRejection(
                    new CompleteCmd(rejection.getId(), "REJECT", rejection.getComment(), null),
                    rejection.getApprovedBy() == null ? operatorId : rejection.getApprovedBy(),
                    force, rejection, pi, root);
                return;
            }
        } else {
            // OR-sign: skip sibling PENDING tasks on same node.
            List<TaskEntity> siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId())
                .eq("status", "PENDING")
                .eq("node_id", t.getNodeId())
                .ne("id", t.getId()));
            for (TaskEntity sib : siblings) {
                sib.setStatus("SKIPPED");
                taskMapper.updateById(sib);
                insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                    "SKIP", operatorId, "OR-sign short-circuit");
            }
        }

        JsonNode formData = readFormData(pi.getFormDataId());
        if (t.getParallelId() != null) {
            advanceParallelBranch(root, pi, formData, t.getParallelId(), t.getBranchId(), cur);
            return;
        }
        // 仅首轮 start 时传入过 selfSelected；后续（理论上不会出现）传空 map。
        resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
    }

    @Override
    public void advanceParallelBranch(JsonNode root, ProcessInstance instance,
                                      JsonNode formData, String parallelId,
                                      String branchId, JsonNode completedNode) {
        JsonNode node = ProcessTreeNav.next(root, completedNode, parallelId);
        NodeContext context = new NodeContext(
            instance.getStartedBy(), formData, Map.of(), completedNode.path("id").asText(null),
            parallelId, branchId
        );
        while (node != null) {
            String type = node.path("type").asText();
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            JsonNode handled = node;
            long nodeInstanceId = runtimeV2 == null ? 0
                : runtimeV2.enterNode(instance, handled, context);
            NodeOutcome outcome = handler.handle(root, handled, instance,
                nodeInstanceId == 0 ? context : context.atNode(nodeInstanceId));
            if (outcome.type() == NodeOutcome.Type.HALT) {
                if (outcome instanceof NodeOutcome.Halt halt) {
                    notifyAssigned(instance.getId(), halt.newTaskIds());
                }
                return;
            }
            if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
            if (outcome.type() == NodeOutcome.Type.END) return;
            node = outcome.node();
            context = new NodeContext(
                instance.getStartedBy(), formData, Map.of(), handled.path("id").asText(null),
                parallelId, branchId
            );
        }

        if (runtimeV2 != null && runtimeV2.active(instance)) {
            runtimeV2.parallelBranchPassed(instance, root, parallelId, branchId);
        }

        Long stillPending = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .eq("parallel_id", parallelId)
            .eq("status", "PENDING"));
        if ((stillPending != null && stillPending > 0)
            || hasActiveParallelAutomation(root, instance.getId(), parallelId)) {
            processInstanceMapper.updateById(instance);
            return;
        }
        JsonNode parallelNode = ProcessTreeNav.findById(root, parallelId);
        if (parallelNode == null) {
            throw new BizException("BAD_FLOW", "parallel node not found: " + parallelId);
        }
        ProcessTreeNav.ParallelParent parent = ProcessTreeNav.findParallelParent(root, parallelId);
        if (parent != null) {
            advanceParallelBranch(root, instance, formData,
                parent.parallelId(), parent.branchId(), parallelNode);
            return;
        }
        processInstanceMapper.updateById(instance);
        resolveAndLand(root, instance, formData, instance.getStartedBy(), Map.of(), parallelNode);
    }

    @Transactional
    public void reject(CompleteCmd cmd, long operatorId) {
        rejectInternal(cmd, operatorId, false);
    }

    @Transactional
    public void forceReject(CompleteCmd cmd, long operatorId) {
        rejectInternal(cmd, operatorId, true);
    }

    private void rejectInternal(CompleteCmd cmd, long operatorId, boolean force) {
        LockedTask locked = lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task cannot be rejected as approval");
        }
        if (!force && t.getParallelId() != null
            && (runtimeV2 == null || !runtimeV2.active(pi))) {
            throw new BizException("PARALLEL_REJECT_DISABLED", "并行审批任务不允许驳回");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        JsonNode root = readProcessTree(pi);
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "current node not in tree: " + t.getNodeId());
        }

        t.setStatus("REJECTED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            t.setActionFormRevisionId(pi.getCurrentFormRevisionId());
        }
        updateTaskOrConflict(t);
        insertHistory(t, null, t.getNodeId(), force ? "FORCE_REJECT_VOTE" : "REJECT_VOTE",
            operatorId, cmd.comment());

        if (runtimeV2 != null && runtimeV2.active(pi) && t.getNodeInstanceId() != null) {
            WorkflowRuntimeV2.Decision decision = t.getOperationKind() != null
                && t.getOperationKind().startsWith("ADD_")
                ? runtimeV2.rejectAdditional(t)
                : runtimeV2.rejectVote(t, cur);
            settleV2Decision(decision, cmd, operatorId, force, t, pi, root, cur);
            return;
        }

        String mode = WorkflowRuntimeV2.mode(cur);
        if (!force && ("ALL".equals(mode) || "RATIO".equals(mode))) {
            List<TaskEntity> votes = legacySettler.legacyNodeVotes(t);
            if (votes.stream().anyMatch(task -> "PENDING".equals(task.getStatus()))) return;
            long approved = votes.stream()
                .filter(task -> "APPROVED".equals(task.getStatus())).count();
            if ("RATIO".equals(mode) && approved >= LegacyDecisionSettler.requiredApprovals(votes.size(), cur)) {
                JsonNode formData = readFormData(pi.getFormDataId());
                resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
                return;
            }
            TaskEntity rejection = votes.stream()
                .filter(task -> "REJECTED".equals(task.getStatus()))
                .findFirst().orElse(t);
            legacySettler.settleLegacyRejection(
                new CompleteCmd(rejection.getId(), "REJECT", rejection.getComment(), null),
                rejection.getApprovedBy() == null ? operatorId : rejection.getApprovedBy(),
                false, rejection, pi, root);
            return;
        }
        legacySettler.settleLegacyRejection(cmd, operatorId, force, t, pi, root);
    }

    @Transactional
    public List<Long> resubmitRework(long taskId, long operatorId) {
        LockedTask locked = lockPendingTask(taskId);
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


    /** V1 驳回结算已下沉到 {@link LegacyDecisionSettler}；这里只保留调用点，便于对照分流。 */
    private void settleLegacyRejection(CompleteCmd command, long operatorId, boolean force,
                                       TaskEntity task, ProcessInstance instance, JsonNode root) {
        legacySettler.settleLegacyRejection(command, operatorId, force, task, instance, root);
    }

    /** V2 结算已下沉到 {@link V2DecisionSettler}；这里保留同名调用点，便于对照分流。 */
    private void settleV2Decision(WorkflowRuntimeV2.Decision decision, CompleteCmd command,
                                  long operatorId, boolean force, TaskEntity acted,
                                  ProcessInstance instance, JsonNode root, JsonNode current) {
        v2Settler.settleV2Decision(decision, command, operatorId, force, acted, instance, root,
            current);
    }

    private LockedTask lockPendingTask(long taskId) {
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
        if (runtimeV2 != null) runtimeV2.requireActiveTask(current, instance);
        return new LockedTask(current, instance);
    }

    private void updateTaskOrConflict(TaskEntity task) {
        support.updateTaskOrConflict(task);
    }

    private record LockedTask(TaskEntity task, ProcessInstance instance) { }

    /**
     * 委托任务（delegatedFrom != null 且非加签）审批时，原任务若仍为 PENDING，
     * 则由委托任务替代原任务完成，避免 AND 模式把“同一审批人的镜像任务”算成两个人。
     */
    private void skipDelegatedParentIfNeeded(TaskEntity task, long operatorId) {
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
        insertHistoryOnInstance(parent.getProcInstId(), parent.getNodeId(), parent.getNodeId(),
            "SKIP", operatorId, "delegated task acted");
    }

    /**
     * 审批节点可编辑字段回写：只允许修改当前节点 formPerms 中标记为 EDITABLE 的字段，
     * 逐字段按 schema 校验后合并进 t_form_data.data（未提交的字段保持不变）。
     */
    private void applyNodeFieldEdits(ProcessInstance pi, JsonNode cur, Object data,
                                     long operatorId) {
        if (data instanceof Map<?, ?> edits && edits.isEmpty()) {
            return;
        }
        Set<String> editable = editableFieldIds(cur);
        if (editable.isEmpty()) {
            throw new BizException("FORM_DATA_INVALID", "该审批节点不允许编辑表单字段");
        }
        if (!(data instanceof Map<?, ?> edits)) {
            throw new BizException("FORM_DATA_INVALID", "表单字段更新必须是对象");
        }
        for (Object key : edits.keySet()) {
            if (!(key instanceof String fieldId) || !editable.contains(fieldId)) {
                throw new BizException("FORM_DATA_INVALID", "字段不可编辑: " + key);
            }
        }
        FormData formData = formDataMapper.selectRuntimeById(pi.getFormDataId());
        if (formData == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }
        FormDefinition fd = formDefinitionService.getById(formData.getFormDefId());
        String schema = runtimeV2 == null ? null : runtimeV2.formSchema(pi);
        if (fd == null && schema == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        var merged = (com.fasterxml.jackson.databind.node.ObjectNode)
            readTreeOrEmpty(formData.getData()).deepCopy();
        edits.forEach((key, value) -> merged.set((String) key, json.valueToTree(value)));
        formDefinitionService.validateSubmission(schema == null ? fd.getSchema() : schema,
            merged, editable);
        formData.setData(writeJson(merged));
        formDataMapper.updateById(formData);
        Map<String, String> modes = new LinkedHashMap<>();
        readTree(schema == null ? fd.getSchema() : schema).forEach(node ->
            modes.put(node.path("id").asText(),
                editable.contains(node.path("id").asText()) ? "EDITABLE" : "READONLY"));
        mediaValidator.validate(formData.getId(), modes);
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            long revisionId = runtimeV2.createRevision(formData, "SUBMITTED", "NODE_EDIT",
                operatorId);
            pi.setCurrentFormRevisionId(revisionId);
            processInstanceMapper.updateById(pi);
        }
    }

    private Set<String> editableFieldIds(JsonNode node) {
        var perms = node.path("props").path("formPerms");
        var ids = new LinkedHashSet<String>();
        if (perms.isArray()) {
            for (var entry : perms) {
                if ("EDITABLE".equals(entry.path("mode").asText())) {
                    ids.add(entry.path("fieldId").asText());
                }
            }
        }
        return ids;
    }


    // -----------------------------------------------------------------------
    // 核心：resolveAndLand — 从刚完成/起点节点起沿树前进，直到落到一个需要建
    // 任务的 APPROVAL 节点（可能多条）、走完末端、或实例结束。
    // -----------------------------------------------------------------------

    /**
     * @param root          流程树根（用于历史记录）
     * @param pi            当前实例
     * @param formData      当前表单数据（条件求值用）
     * @param starterId     发起人 id
     * @param selfSelected  自选审批人映射
     * @param fromNode      刚完成的节点（首轮 = root）
     * @return 新建的任务 id 列表
     */
    @Override
    public List<Long> resolveAndLand(JsonNode root, ProcessInstance pi,
                                     JsonNode formData, long starterId,
                                     Map<String, List<Long>> selfSelected,
                                     JsonNode fromNode) {
        JsonNode node = ProcessTreeNav.next(root, fromNode, null);
        List<Long> taskIds = resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            fromNode, node);
        notifyAssigned(pi.getId(), taskIds);
        return taskIds;
    }

    /**
     * 驳回到节点专用：从指定 target 节点自身（而不是其 children）开始推进，
     * 让 target 自身被重新评估（建任务 / AUTO_PASS / 继续 children）。
     * fromNode 设为 null，使 ARRIVE/CC/AUTO_PASS/COMPLETE 历史行的 from 字段为空。
     */
    @Override
    public List<Long> resolveAndLandFromNode(JsonNode root, ProcessInstance pi,
                                             JsonNode formData, long starterId,
                                             Map<String, List<Long>> selfSelected,
                                             JsonNode targetNode) {
        List<Long> taskIds = resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            null, targetNode);
        notifyAssigned(pi.getId(), taskIds);
        return taskIds;
    }

    /** 真正的循环实现：startNode 为入口节点（可能为 null）。 */
    private List<Long> resolveAndLandLoop(JsonNode root, ProcessInstance pi,
                                          JsonNode formData, long starterId,
                                          Map<String, List<Long>> selfSelected,
                                          JsonNode fromNode, JsonNode startNode) {
        JsonNode node = startNode;
        NodeContext ctx = new NodeContext(starterId, formData, selfSelected, fromNode == null ? null : fromNode.path("id").asText(null), null, null);
        while (true) {
            if (node == null) {
                // 末端 → 实例 APPROVED
                pi.setStatus("APPROVED");
                pi.setFinishedAt(OffsetDateTime.now());
                pi.setCurrentNodeId(null);
                processInstanceMapper.updateById(pi);
                insertHistoryOnInstance(pi.getId(),
                    fromNode == null ? null : fromNode.path("id").asText(null),
                    null, "COMPLETE", pi.getStartedBy(), null);
                if (runtimeV2 != null && runtimeV2.active(pi)) {
                    runtimeV2.outbox(pi.getId(), pi.getStartedBy(), "INSTANCE_APPROVED", null);
                } else {
                    notifier.publish(new NotificationEvent(this, "INSTANCE_APPROVED",
                        pi.getId(), null, pi.getStartedBy(),
                        "实例 #" + pi.getId() + " 已审批通过"));
                }
                return List.of();
            }
            String type = node.path("type").asText();
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            long nodeInstanceId = runtimeV2 == null ? 0 : runtimeV2.enterNode(pi, node, ctx);
            NodeOutcome outcome = handler.handle(root, node, pi,
                nodeInstanceId == 0 ? ctx : ctx.atNode(nodeInstanceId));
            switch (outcome.type()) {
                case NEXT:
                    if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
                    String nextFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, nextFromId, null, null);
                    continue;
                case JUMP:
                    if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
                    String jumpFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, jumpFromId, null, null);
                    continue;
                case END:
                    if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
                    processInstanceMapper.updateById(pi);
                    insertHistoryOnInstance(pi.getId(), node.path("id").asText(null),
                        null, "COMPLETE", pi.getStartedBy(), null);
                    return List.of();
                case HALT:
                default:
                    // handler 已建 PENDING 任务并写 ARRIVE 历史；保存实例并返回
                    processInstanceMapper.updateById(pi);
                    if (outcome instanceof NodeOutcome.Halt h) {
                        return h.newTaskIds();
                    }
                    return List.of();
            }
        }
    }

    private NodeHandler pickHandler(String type) {
        for (NodeHandler h : nodeHandlers) {
            if (h.supports(type)) return h;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // helpers —— 实现全部在 EngineSupport（同一批 mapper / 同一套错误码）。
    // 这里保留同名薄壳而不直接写 support.xxx：调用点有几十处，改动量就是风险量。
    // -----------------------------------------------------------------------

    private JsonNode readTree(String s) {
        return support.readTree(s);
    }

    private JsonNode readTreeOrEmpty(String s) {
        return support.readTreeOrEmpty(s);
    }

    private JsonNode readFormData(Long formDataId) {
        return support.readFormData(formDataId);
    }

    /** 旧运行快照只在内存中按当前表单 Schema 兼容 label，绝不回写。 */
    private JsonNode readProcessTree(ProcessInstance instance) {
        return support.readProcessTree(instance);
    }

    private boolean hasActiveParallelAutomation(JsonNode root, Long instanceId,
                                                String parallelId) {
        List<WorkflowJob> active = workflowJobMapper.selectList(
            new QueryWrapper<WorkflowJob>()
                .eq("proc_inst_id", instanceId)
                .eq("blocking", true)
                .in("status", List.of("SCHEDULED", "RUNNING", "FAILED")));
        return active != null && active.stream().anyMatch(job ->
            ProcessTreeNav.isInsideParallel(root, parallelId, job.getNodeId()));
    }

    private void notifyAssigned(Long instanceId, List<Long> taskIds) {
        for (Long taskId : taskIds) {
            TaskEntity task = taskMapper.selectById(taskId);
            if (task != null) {
                if (task.getNodeInstanceId() != null) continue;
                notifier.publish(new NotificationEvent(this, "TASK_ASSIGNED",
                    instanceId, taskId, task.getAssigneeId(),
                    "新任务 #" + taskId + " 节点 " + task.getNodeId()));
            }
        }
    }

    private void insertHistory(TaskEntity t, String from, String to,
                                String action, Long operatorId, String comment) {
        support.insertHistory(t, from, to, action, operatorId, comment);
    }

    private void insertHistoryOnInstance(Long instId, String from, String to,
                                          String action, Long operatorId, String comment) {
        support.insertHistoryOnInstance(instId, from, to, action, operatorId, comment);
    }

    private String writeJson(Object o) {
        return support.writeJson(o);
    }
}
