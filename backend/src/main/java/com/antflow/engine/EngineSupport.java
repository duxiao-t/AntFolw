package com.antflow.engine;

import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 引擎各协作者共用的读写底座：JSON 解析、表单/流程树读取、历史写入、退回任务构造。
 *
 * <p>抽出来的理由不是"减少行数"，而是这五处（V1 结算、V2 结算、返工、撤回、自动化）必须
 * 写**同一种**历史行、用**同一套**错误码读树。任何一份复制，日后都会各自漂移：
 * 例如把 {@code BAD_FLOW_JSON} 写成 {@code BAD_JSON}，就只有某一条路径的报错变了。
 * 方法体与拆分前逐字一致，只有可见性与调用前缀变了。
 *
 * <p>刻意**不做成 Spring bean**：它要用的 mapper 与 ProcessEngine 是同一批实例。
 * 如果另起一份注入，单测里就会出现"两套 mock"，读到的数据与断言的不是同一份。
 */
final class EngineSupport {

    private final ObjectMapper json;
    private final TaskMapper taskMapper;
    private final FormDataMapper formDataMapper;
    private final TaskHistoryMapper historyMapper;
    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    /**
     * 用 Supplier 而不是直接持引用：runtimeV2 在 ProcessEngine 上是 Spring 字段注入，
     * 构造期尚未赋值；单测还会在构造后把它换掉（见 TreeRuntimeFixture）。
     */
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    EngineSupport(ObjectMapper json, TaskMapper taskMapper, FormDataMapper formDataMapper,
                  TaskHistoryMapper historyMapper, FormDefinitionService formDefinitionService,
                  ProcessDefinitionService processDefinitionService,
                  Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.json = json;
        this.taskMapper = taskMapper;
        this.formDataMapper = formDataMapper;
        this.historyMapper = historyMapper;
        this.formDefinitionService = formDefinitionService;
        this.processDefinitionService = processDefinitionService;
        this.runtimeV2 = runtimeV2;
    }

    JsonNode readTree(String s) {
        if (s == null || s.isBlank()) {
            throw new BizException("BAD_FLOW_JSON", "process tree is empty");
        }
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    JsonNode readTreeOrEmpty(String s) {
        if (s == null || s.isBlank()) return json.createObjectNode();
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    JsonNode readFormData(Long formDataId) {
        FormData fd = formDataMapper.selectRuntimeById(formDataId);
        if (fd == null) return json.createObjectNode();
        return readTreeOrEmpty(fd.getData());
    }

    /** 旧运行快照只在内存中按当前表单 Schema 兼容 label，绝不回写。 */
    JsonNode readProcessTree(ProcessInstance instance) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        if (v2 != null && v2.active(instance)) {
            return readTree(v2.processTree(instance));
        }
        FormData data = formDataMapper.selectRuntimeById(instance.getFormDataId());
        FormDefinition form = data == null ? null
            : formDefinitionService.getById(data.getFormDefId());
        if (form == null) return readTree(instance.getProcessSnapshot());
        String normalized = processDefinitionService.normalizeConditionValues(
            instance.getProcessSnapshot(), form.getSchema());
        return readTree(normalized == null ? instance.getProcessSnapshot() : normalized);
    }

    String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    /** 上一级已通过的审批节点；V1/V2 的退回目标都从它推。 */
    TaskEntity previousApproval(TaskEntity current) {
        OffsetDateTime before = current.getCreatedAt() == null
            ? current.getApprovedAt() : current.getCreatedAt();
        QueryWrapper<TaskEntity> query = new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", current.getProcInstId())
            .eq("status", "APPROVED")
            .isNull("parallel_id")
            .ne("node_id", current.getNodeId());
        if (before != null) {
            query.lt("approved_at", before);
        }
        query.orderByDesc("approved_at").orderByDesc("id").last("LIMIT 1");
        return taskMapper.selectOne(query);
    }

    List<Long> previousNodeAssignees(TaskEntity previous) {
        List<TaskEntity> rows = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", previous.getProcInstId())
            .eq("node_id", previous.getNodeId())
            .eq("status", "APPROVED")
            .isNull("parallel_id")
            .orderByAsc("id"));
        List<Long> assignees = rows.stream()
            .filter(task -> Objects.equals(previous.getNodeId(), task.getNodeId())
                && "APPROVED".equals(task.getStatus())
                && task.getAssigneeId() != null)
            .map(TaskEntity::getAssigneeId)
            .distinct()
            .collect(Collectors.toList());
        if (assignees.isEmpty()) {
            return previous.getAssigneeId() == null ? List.of() : List.of(previous.getAssigneeId());
        }
        return assignees;
    }

    static TaskEntity newReturnedTask(Long procInstId, String nodeId, String taskType,
                                      String approvalMode, Long assigneeId,
                                      String parallelId, String branchId) {
        TaskEntity task = new TaskEntity();
        task.setProcInstId(procInstId);
        task.setNodeId(nodeId);
        task.setTaskType(taskType);
        task.setStatus("PENDING");
        task.setApprovalMode(approvalMode);
        task.setAssigneeId(assigneeId);
        task.setParallelId(parallelId);
        task.setBranchId(branchId);
        return task;
    }

    /** 乐观并发：updateById 必须命中 1 行，否则说明任务已被别人改过。 */
    void updateTaskOrConflict(TaskEntity task) {
        if (taskMapper.updateById(task) != 1) {
            throw new BizException("CONCURRENT_CONFLICT", "Task changed concurrently");
        }
    }

    void insertHistory(TaskEntity t, String from, String to,
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

    void insertHistoryOnInstance(Long instId, String from, String to,
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
}
