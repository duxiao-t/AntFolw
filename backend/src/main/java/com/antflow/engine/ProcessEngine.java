package com.antflow.engine;

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
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
 *   <li>{@link #reject(CompleteCmd, long)} — 标记 REJECTED、跳兄弟、实例 REJECTED。</li>
 *   <li>{@link #withdraw(long, long)} — 发起人在任意任务被处理前撤回。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProcessEngine {

    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessDefinitionService processDefinitionService;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final TaskMapperExt taskMapperExt;
    private final TaskHistoryMapper historyMapper;
    private final List<NodeHandler> nodeHandlers;
    private final ObjectMapper json;

    @Transactional
    public Map<String, Object> start(StartCmd cmd, long userId) {
        FormDefinition fd = formDefinitionService.getByCode(cmd.formCode());
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + cmd.formCode());
        }
        ProcessDefinition pd = processDefinitionService.latestPublishedForForm(fd.getId());
        if (pd == null) {
            throw new BizException("NO_FLOW", "No published process for form " + cmd.formCode());
        }

        FormData fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setData(writeJson(cmd.data()));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        formDataMapper.insert(fd2);

        ProcessInstance pi = new ProcessInstance();
        pi.setProcDefId(pd.getId());
        pi.setProcessDefVersion(pd.getVersion());
        pi.setProcessSnapshot(pd.getProcess());  // 冻结流程树，避免后续改版污染已发起实例
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        pi.setStartedAt(OffsetDateTime.now());
        processInstanceMapper.insert(pi);

        // 引擎后续一律走快照树，而非 pd.getProcess()
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode formData = readTreeOrEmpty(fd2.getData());
        Map<String, List<Long>> selfSelected =
            cmd.selfSelected() == null ? Map.of() : cmd.selfSelected();

        List<Long> firstTasks = resolveAndLand(root, pi, formData, userId, selfSelected, root);
        return Map.of(
            "instanceId", pi.getId(),
            "formDataId", fd2.getId(),
            "firstTaskIds", firstTasks
        );
    }

    @Transactional
    public void approve(CompleteCmd cmd, long operatorId) {
        TaskEntity t = taskMapper.selectById(cmd.taskId());
        if (t == null || !"PENDING".equals(t.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        if (!Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        t.setStatus("APPROVED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        taskMapper.updateById(t);
        insertHistory(t, null, t.getNodeId(), "APPROVE", operatorId, cmd.comment());

        ProcessInstance pi = taskMapperExt.selectInstanceById(t.getProcInstId()).orElseThrow();
        // 永远走快照，不依赖 pd.getProcess()（避免流程改版后已发起的实例跑飞）
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "approval node not in tree: " + t.getNodeId());
        }

        String mode = cur.path("props").path("mode").asText("OR");
        boolean andMode = "AND".equals(mode);

        if (andMode) {
            // Wait until all PENDING siblings have acted.
            List<TaskEntity> stillPending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId())
                .eq("status", "PENDING")
                .eq("node_id", t.getNodeId())
                .ne("id", t.getId()));
            if (!stillPending.isEmpty()) {
                return;   // 尚未完成本节点全员
            }
            // All done — first-come OR any-of-several, choose "auto approve" semantics.
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
        // 仅首轮 start 时传入过 selfSelected；后续（理论上不会出现）传空 map。
        resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
    }

    @Transactional
    public void reject(CompleteCmd cmd, long operatorId) {
        TaskEntity t = taskMapper.selectById(cmd.taskId());
        if (t == null || !"PENDING".equals(t.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        if (!Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        t.setStatus("REJECTED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        taskMapper.updateById(t);
        insertHistory(t, null, t.getNodeId(), "REJECT", operatorId, cmd.comment());

        ProcessInstance pi = taskMapperExt.selectInstanceById(t.getProcInstId()).orElseThrow();
        // 同节点兄弟一律 SKIPPED
        List<TaskEntity> siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).eq("status", "PENDING")
            .eq("node_id", t.getNodeId()).ne("id", t.getId()));
        for (TaskEntity sib : siblings) {
            sib.setStatus("SKIPPED");
            taskMapper.updateById(sib);
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                "SKIP", operatorId, null);
        }

        // === 驳回路由 ===
        // 默认 TO_END（保持 MVP 行为）；TO_NODE 表示驳回到指定节点重审。
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "current node not in tree: " + t.getNodeId());
        }
        String refuseMode = cur.path("props").path("refuse").path("mode").asText("TO_END");
        String refuseTarget = cur.path("props").path("refuse").path("targetNodeId").asText(null);

        // 运行时覆盖：前端传入 rejectToNodeId 时优先
        if (cmd.rejectToNodeId() != null && !cmd.rejectToNodeId().isBlank()) {
            refuseMode = "TO_NODE";
            refuseTarget = cmd.rejectToNodeId();
        }

        if ("TO_NODE".equals(refuseMode) && refuseTarget != null && !refuseTarget.isBlank()) {
            JsonNode target = ProcessTreeNav.findById(root, refuseTarget);
            if (target == null) {
                throw new BizException("BAD_FLOW",
                    "refuse target node not in tree: " + refuseTarget);
            }
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), refuseTarget,
                "REJECT_TO_NODE", operatorId, cmd.comment());
            // 沿 target 重新推进：把 target 当作"起点节点"（不是其 children），
            // 这样 target 自身会被重新评估（建任务或继续走）。
            JsonNode formData = readFormData(pi.getFormDataId());
            resolveAndLandFromNode(root, pi, formData, pi.getStartedBy(), Map.of(), target);
            return;
        }

        // TO_END 或未配置 —— 实例终止（保持 MVP 行为）
        pi.setStatus("REJECTED");
        pi.setFinishedAt(OffsetDateTime.now());
        pi.setCurrentNodeId(null);
        processInstanceMapper.updateById(pi);
        insertHistoryOnInstance(pi.getId(), t.getNodeId(), null,
            "REJECT", operatorId, cmd.comment());
    }

    @Transactional
    public void withdraw(long instanceId, long operatorId) {
        ProcessInstance pi = processInstanceMapper.selectById(instanceId);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        if (!Objects.equals(pi.getStartedBy(), operatorId)) {
            throw new AccessDeniedException("only starter can withdraw");
        }
        if (!"RUNNING".equals(pi.getStatus())) {
            throw new BizException("BAD_STATE", "instance not running");
        }
        List<TaskEntity> anyDone = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).ne("status", "PENDING"));
        if (!anyDone.isEmpty()) {
            throw new BizException("ALREADY_ACTED",
                "cannot withdraw after a task has been acted on");
        }
        List<TaskEntity> pending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).eq("status", "PENDING"));
        for (TaskEntity p : pending) {
            p.setStatus("SKIPPED");
            taskMapper.updateById(p);
        }
        pi.setStatus("WITHDRAWN");
        pi.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(pi);
        insertHistoryOnInstance(pi.getId(), null, pi.getCurrentNodeId(),
            "WITHDRAW", operatorId, null);
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
    private List<Long> resolveAndLand(JsonNode root, ProcessInstance pi,
                                      JsonNode formData, long starterId,
                                      Map<String, List<Long>> selfSelected,
                                      JsonNode fromNode) {
        JsonNode node = ProcessTreeNav.childrenOf(fromNode);
        return resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            fromNode, node);
    }

    /**
     * 驳回到节点专用：从指定 target 节点自身（而不是其 children）开始推进，
     * 让 target 自身被重新评估（建任务 / AUTO_PASS / 继续 children）。
     * fromNode 设为 null，使 ARRIVE/CC/AUTO_PASS/COMPLETE 历史行的 from 字段为空。
     */
    private List<Long> resolveAndLandFromNode(JsonNode root, ProcessInstance pi,
                                               JsonNode formData, long starterId,
                                               Map<String, List<Long>> selfSelected,
                                               JsonNode targetNode) {
        return resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            null, targetNode);
    }

    /** 真正的循环实现：startNode 为入口节点（可能为 null）。 */
    private List<Long> resolveAndLandLoop(JsonNode root, ProcessInstance pi,
                                          JsonNode formData, long starterId,
                                          Map<String, List<Long>> selfSelected,
                                          JsonNode fromNode, JsonNode startNode) {
        JsonNode node = startNode;
        NodeContext ctx = new NodeContext(starterId, formData, selfSelected,
            fromNode == null ? null : fromNode.path("id").asText(null));
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
                return List.of();
            }
            String type = node.path("type").asText();
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            NodeOutcome outcome = handler.handle(root, node, pi, ctx);
            switch (outcome.type()) {
                case NEXT:
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected,
                        node == null ? null : node.path("id").asText(null));
                    continue;
                case JUMP:
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected,
                        node == null ? null : node.path("id").asText(null));
                    continue;
                case END:
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

    private JsonNode readTree(String s) {
        if (s == null || s.isBlank()) {
            throw new BizException("BAD_FLOW_JSON", "process tree is empty");
        }
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private JsonNode readTreeOrEmpty(String s) {
        if (s == null || s.isBlank()) return json.createObjectNode();
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private JsonNode readFormData(Long formDataId) {
        FormData fd = formDataMapper.selectById(formDataId);
        if (fd == null) return json.createObjectNode();
        return readTreeOrEmpty(fd.getData());
    }

    private void insertHistory(TaskEntity t, String from, String to,
                                String action, Long operatorId, String comment) {
        TaskHistoryEntity h = new TaskHistoryEntity();
        h.setProcInstId(t.getProcInstId());
        h.setTaskId(t.getId());
        h.setFromNodeId(from);
        h.setToNodeId(to);
        h.setAction(action);
        h.setOperatorId(operatorId);
        h.setComment(comment);
        historyMapper.insert(h);
    }

    private void insertHistoryOnInstance(Long instId, String from, String to,
                                          String action, Long operatorId, String comment) {
        TaskHistoryEntity h = new TaskHistoryEntity();
        h.setProcInstId(instId);
        h.setFromNodeId(from);
        h.setToNodeId(to);
        h.setAction(action);
        h.setOperatorId(operatorId);
        h.setComment(comment);
        historyMapper.insert(h);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
