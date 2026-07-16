package com.antflow.engine;

import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.handler.NodeDispatcher;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
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
import java.util.*;

/**
 * Custom lightweight approval engine — sequential MVP (spec decision #3, #20).
 *
 * Engagement points:
 *   - {@link #start(StartCmd, long)} — creates FormData (SUBMITTED, with form_def_version snapshot)
 *     + ProcessInstance (RUNNING) + first wave of {@code TaskEntity} records (PENDING, OR_SIGN).
 *   - {@link #approve(CompleteCmd, long)} — advances the instance; siblings SKIPPED on OR-sign.
 *   - {@link #reject(CompleteCmd, long)} — terminates the instance REJECTED + sibling SKIPPED.
 *   - {@link #withdraw(long, long)} — starter can withdraw before any task is acted on.
 *
 * Concurrency: optimistic locking on {@code t_process_instance.version} and
 * {@code t_task.version} via MyBatis-Plus (see {@link com.antflow.common.MybatisPlusConfig}).
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
    private final AssigneeResolver assigneeResolver;
    private final NodeDispatcher dispatcher;
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
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        pi.setStartedAt(OffsetDateTime.now());
        processInstanceMapper.insert(pi);

        List<Long> firstTasks = advance(pd, pi, "start");
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
        ProcessDefinition pd = processDefinitionService.getById(pi.getProcDefId());

        List<JsonNode> next = nextNodes(pd, t.getNodeId());
        // MVP: filter out `end` nodes from "tasks to create"; if only end-nodes remain,
        // the instance terminates.
        List<JsonNode> produceTasks = next.stream()
            .filter(n -> !"end".equals(n.path("type").asText()))
            .toList();

        if (produceTasks.isEmpty()) {
            pi.setStatus("APPROVED");
            pi.setFinishedAt(OffsetDateTime.now());
            processInstanceMapper.updateById(pi);
            insertHistoryOnInstance(pi.getId(), null, null, "COMPLETE", operatorId, null);
            return;
        }

        for (JsonNode nn : produceTasks) {
            AssigneeSpec spec = parseAssignee(nn.path("assignee"));
            List<Long> assignees = assigneeResolver.resolve(nn.path("id").asText(), spec);
            for (Long a : assignees) {
                TaskEntity nt = new TaskEntity();
                nt.setProcInstId(pi.getId());
                nt.setNodeId(nn.path("id").asText());
                nt.setAssigneeId(a);
                nt.setStatus("PENDING");
                nt.setApprovalMode("OR_SIGN");
                taskMapper.insert(nt);
            }
            pi.setCurrentNodeId(nn.path("id").asText());
        }
        processInstanceMapper.updateById(pi);

        // OR-sign short-circuit: skip sibling tasks on the just-completed node.
        var pendingSiblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId())
            .eq("status", "PENDING")
            .eq("node_id", t.getNodeId())
            .ne("id", t.getId()));
        for (TaskEntity sib : pendingSiblings) {
            sib.setStatus("SKIPPED");
            taskMapper.updateById(sib);
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                "SKIP", operatorId, "OR-sign short-circuit");
        }
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
        // Sibling SKIP
        var pendingSiblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).eq("status", "PENDING")
            .eq("node_id", t.getNodeId()).ne("id", t.getId()));
        for (TaskEntity sib : pendingSiblings) {
            sib.setStatus("SKIPPED");
            taskMapper.updateById(sib);
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                "SKIP", operatorId, null);
        }
        pi.setStatus("REJECTED");
        pi.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(pi);
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
        var anyDone = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).ne("status", "PENDING"));
        if (!anyDone.isEmpty()) {
            throw new BizException("ALREADY_ACTED",
                "cannot withdraw after a task has been acted on");
        }
        var pending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
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

    /**
     * Walk from {@code fromNodeId}; create one TaskEntity per resolved assignee; advance
     * the instance's {@code current_node_id}; emit START history.
     */
    private List<Long> advance(ProcessDefinition pd, ProcessInstance pi, String fromNodeId) {
        List<JsonNode> next = nextNodes(pd, fromNodeId);
        List<JsonNode> produceTasks = next.stream()
            .filter(n -> !"end".equals(n.path("type").asText()))
            .toList();
        if (produceTasks.isEmpty()) {
            pi.setStatus("APPROVED");
            pi.setFinishedAt(OffsetDateTime.now());
            processInstanceMapper.updateById(pi);
            insertHistoryOnInstance(pi.getId(), fromNodeId, null, "COMPLETE", pi.getStartedBy(), null);
            return List.of();
        }
        List<Long> newTaskIds = new ArrayList<>();
        for (var nn : produceTasks) {
            var spec = parseAssignee(nn.path("assignee"));
            var assignees = assigneeResolver.resolve(nn.path("id").asText(), spec);
            for (Long a : assignees) {
                TaskEntity nt = new TaskEntity();
                nt.setProcInstId(pi.getId());
                nt.setNodeId(nn.path("id").asText());
                nt.setAssigneeId(a);
                nt.setStatus("PENDING");
                nt.setApprovalMode("OR_SIGN");
                taskMapper.insert(nt);
                newTaskIds.add(nt.getId());
            }
            pi.setCurrentNodeId(nn.path("id").asText());
        }
        processInstanceMapper.updateById(pi);
        insertHistoryOnInstance(pi.getId(), fromNodeId, pi.getCurrentNodeId(),
            "START", pi.getStartedBy(), null);
        return newTaskIds;
    }

    private List<JsonNode> nextNodes(ProcessDefinition pd, String fromId) {
        throw new UnsupportedOperationException("流程引擎将于 Task 7 重写为树遍历");
    }

    private AssigneeSpec parseAssignee(JsonNode n) {
        var type = n.path("type").asText();
        var ids = new ArrayList<>();
        n.path("ids").forEach(x -> ids.add(x.asLong()));
        return new AssigneeSpec(type, ids);
    }

    private void insertHistory(TaskEntity t, String from, String to,
                                String action, Long operatorId, String comment) {
        var h = new TaskHistoryEntity();
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
        var h = new TaskHistoryEntity();
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
