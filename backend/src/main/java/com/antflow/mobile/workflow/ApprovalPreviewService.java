package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.engine.WorkflowRuntimeV2;
import com.antflow.engine.condition.ConditionEvaluator;
import com.antflow.engine.handler.NodeContext;
import com.antflow.engine.resolver.ApprovalAssigneeSpecs;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection of the first manual approval wave. */
@Service
@RequiredArgsConstructor
public class ApprovalPreviewService {
    private static final String PUBLISHED = "PUBLISHED";

    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final AssigneeResolver assigneeResolver;
    private final WorkflowRuntimeV2 runtimeV2;
    private final ConditionEvaluator conditionEvaluator;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public ApprovalPreviewDto preview(String code, ApprovalPreviewRequest request, long userId) {
        ApprovalPreviewRequest safeRequest = request == null
            ? new ApprovalPreviewRequest(null, Map.of(), null) : request;
        PreviewContext context = safeRequest.reworkTaskId() == null
            ? newSubmission(code, safeRequest, userId)
            : rework(code, safeRequest, userId);
        if (context == null) return new ApprovalPreviewDto(List.of());

        List<PreviewNode> previewNodes = distinct(walk(
            ProcessTreeNav.childrenOf(context.root()), null, false, context));
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        previewNodes.forEach(node -> node.assignments().forEach(
            assignment -> userIds.add(assignment.actualUserId())));
        Map<Long, User> users = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(user -> users.put(user.getId(), user));
        }
        return new ApprovalPreviewDto(previewNodes.stream().map(node ->
            new ApprovalPreviewNodeDto(
                node.node().path("id").asText(),
                nodeName(node.node()),
                WorkflowRuntimeV2.mode(node.node()),
                node.deferred(),
                node.assignments().stream()
                    .map(WorkflowRuntimeV2.Assignment::actualUserId)
                    .distinct()
                    .map(id -> new ApprovalPreviewAssigneeDto(id, userName(users.get(id), id)))
                    .toList()))
            .toList());
    }

    private PreviewContext newSubmission(String code, ApprovalPreviewRequest request,
                                         long userId) {
        authorizationService.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_START);
        FormDefinition form = formDefinitionService.getByCode(code);
        if (form == null || !PUBLISHED.equals(form.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        authorizationService.requireFormAction(form.getId(), PermissionCodes.FORM_RUNTIME_READ);
        ProcessDefinition process = processDefinitionService.latestPublishedForForm(form.getId());
        if (process == null) return null;
        String processJson = processDefinitionService.normalizeConditionValues(
            process.getProcess(), form.getSchema());
        Map<String, Object> data = formDefinitionService.canonicalizeStarterSubmission(
            form.getSchema(), request.data(), processJson);
        return new PreviewContext(readObject(processJson), objectMapper.valueToTree(data), userId,
            form.getId(), safeSelections(request.selfSelected()), null);
    }

    private PreviewContext rework(String code, ApprovalPreviewRequest request, long userId) {
        TaskEntity task = taskMapper.selectById(request.reworkTaskId());
        if (task == null) throw new BizException("NOT_FOUND", "task not found");
        if (!"PENDING".equals(task.getStatus()) || !"REWORK".equals(task.getTaskType())) {
            throw new BizException("TASK_NOT_PENDING", "待修改任务已处理");
        }
        if (!Objects.equals(task.getAssigneeId(), userId)) {
            throw new AccessDeniedException("not your rework task");
        }
        ProcessInstance instance = instanceMapper.selectById(task.getProcInstId());
        if (instance == null) throw new BizException("NOT_FOUND", "instance not found");
        FormData formData = formDataMapper.selectById(instance.getFormDataId());
        if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        if (form == null || !Objects.equals(form.getCode(), code)) {
            throw new BizException("BAD_REQUEST", "rework task does not belong to form");
        }
        String processJson = runtimeV2.active(instance)
            ? runtimeV2.processTree(instance)
            : processDefinitionService.normalizeConditionValues(
                instance.getProcessSnapshot(), form.getSchema());
        String schema = runtimeV2.active(instance) ? runtimeV2.formSchema(instance) : null;
        if (schema == null) schema = form.getSchema();
        Map<String, Object> data = formDefinitionService.canonicalizeStarterRevision(
            schema, request.data(), formData.getData(), processJson);
        formDefinitionService.validateStarterSubmission(schema, data, processJson);
        return new PreviewContext(readObject(processJson), objectMapper.valueToTree(data),
            instance.getStartedBy(), form.getId(), previousSelections(instance.getId()), instance);
    }

    private List<PreviewNode> walk(JsonNode start, String parallelBoundaryId, boolean deferred,
                                   PreviewContext context) {
        JsonNode node = start;
        boolean delayed = deferred;
        while (node != null && !node.isNull() && node.has("id")) {
            switch (node.path("type").asText()) {
                case "EMPTY", "CC", "ROOT" ->
                    node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                case "CONDITIONS" -> node = conditionTarget(node, context.data(), context.root(),
                    parallelBoundaryId);
                case "PARALLEL" -> {
                    List<PreviewNode> parallel = parallel(node, delayed, context);
                    if (!parallel.isEmpty()) return parallel;
                    node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                }
                case "DELAY" -> {
                    delayed = true;
                    node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                }
                case "TRIGGER" -> {
                    if (!"AFTER_SEND".equals(
                        node.path("props").path("continueMode").asText("ON_SUCCESS"))) {
                        delayed = true;
                    }
                    node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                }
                case "APPROVAL" -> {
                    if (context.reworkInstance() != null
                        && runtimeV2.shouldSkipResubmittedNodePreview(context.root(),
                            context.reworkInstance(), node, context.data())) {
                        node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                        continue;
                    }
                    List<Long> assignees = resolveAssignees(node, context);
                    if (assignees.isEmpty()) {
                        node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                        continue;
                    }
                    if (runtimeV2.shouldAutoPassPreview(
                        context.root(), context.starterId(), assignees)) {
                        node = ProcessTreeNav.next(context.root(), node, parallelBoundaryId);
                        continue;
                    }
                    return List.of(new PreviewNode(node, delayed,
                        runtimeV2.previewAssignments(context.formDefId(), node, assignees)));
                }
                default -> throw new BizException("BAD_NODE_TYPE",
                    "未识别节点类型: " + node.path("type").asText());
            }
        }
        return List.of();
    }

    private List<PreviewNode> parallel(JsonNode gateway, boolean deferred,
                                       PreviewContext context) {
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode branch : gateway.withArray("branchs")) {
            if (parallelBranchMatches(branch, context.data())) active.add(branch);
        }
        if (active.isEmpty()) throw new BizException("BAD_FLOW", "并行网关没有可执行分支");
        List<PreviewNode> result = new ArrayList<>();
        for (JsonNode branch : active) {
            JsonNode child = ProcessTreeNav.childrenOf(branch);
            if (child != null) {
                result.addAll(walk(child, gateway.path("id").asText(), deferred, context));
            }
        }
        return distinct(result);
    }

    private JsonNode conditionTarget(JsonNode conditions, JsonNode data, JsonNode root,
                                     String parallelBoundaryId) {
        JsonNode fallback = null;
        for (JsonNode branch : conditions.withArray("branchs")) {
            if (branch.path("props").path("isDefault").asBoolean(false)) {
                fallback = branch;
            } else if (conditionEvaluator.matches(branch.path("props"), data)) {
                JsonNode child = ProcessTreeNav.childrenOf(branch);
                return child == null
                    ? ProcessTreeNav.next(root, conditions, parallelBoundaryId) : child;
            }
        }
        if (fallback == null) throw new BizException("BAD_FLOW", "无匹配条件分支");
        JsonNode child = ProcessTreeNav.childrenOf(fallback);
        return child == null ? ProcessTreeNav.next(root, conditions, parallelBoundaryId) : child;
    }

    private boolean parallelBranchMatches(JsonNode branch, JsonNode data) {
        JsonNode props = branch.path("props");
        String mode = props.path("conditionMode").asText("ALWAYS");
        if ("ALWAYS".equals(mode)) return true;
        if (!"WHEN_MATCHED".equals(mode)) {
            throw new BizException("BAD_FLOW", "并行分支执行方式无效: " + mode);
        }
        JsonNode groups = props.path("groups");
        boolean emptyGroups = groups.isArray() && (!groups.elements().hasNext()
            || java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(groups.elements(), 0), false)
                .allMatch(group -> group.path("conditions").isArray()
                    && group.path("conditions").isEmpty()));
        return emptyGroups || conditionEvaluator.matches(props, data);
    }

    private List<Long> resolveAssignees(JsonNode node, PreviewContext context) {
        AssigneeSpec spec = null;
        List<Long> assignees;
        try {
            if ("FIELD_USER".equals(node.path("props").path("assignedType").asText())) {
                assignees = runtimeV2.fieldUsers(node, new NodeContext(context.starterId(),
                    context.data(), context.selfSelected(), null, null, null));
            } else {
                spec = ApprovalAssigneeSpecs.from(node, context.starterId(),
                    context.selfSelected());
                assignees = assigneeResolver.resolve(node.path("id").asText(), spec);
            }
        } catch (NoAssigneeFoundException exception) {
            assignees = runtimeV2.fallbackUsers(context.root(), node);
            if (assignees.isEmpty() && spec != null
                && "DIRECT_MANAGER".equals(spec.type())) throw exception;
            if (assignees.isEmpty()
                && "TO_PASS".equals(node.path("props").path("nobody")
                    .path("handler").asText("TO_PASS"))) {
                return List.of();
            }
            if (assignees.isEmpty()) throw exception;
        }
        if (assignees.isEmpty()) assignees = runtimeV2.fallbackUsers(context.root(), node);
        if (assignees.isEmpty()) {
            throw new NoAssigneeFoundException(node.path("id").asText(), "no active assignee");
        }
        return assignees;
    }

    private Map<String, List<Long>> previousSelections(long instanceId) {
        Map<String, LinkedHashSet<Long>> grouped = new LinkedHashMap<>();
        taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", instanceId)
                .eq("task_type", "APPROVAL")
                .orderByAsc("id"))
            .forEach(task -> {
                if (task.getAssigneeId() != null) {
                    grouped.computeIfAbsent(task.getNodeId(), ignored -> new LinkedHashSet<>())
                        .add(task.getAssigneeId());
                }
            });
        Map<String, List<Long>> result = new LinkedHashMap<>();
        grouped.forEach((nodeId, ids) -> result.put(nodeId, List.copyOf(ids)));
        return result;
    }

    private static Map<String, List<Long>> safeSelections(
            Map<String, List<Long>> selfSelected) {
        return selfSelected == null ? Map.of() : selfSelected;
    }

    private static List<PreviewNode> distinct(List<PreviewNode> nodes) {
        Map<String, PreviewNode> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.putIfAbsent(node.node().path("id").asText(), node));
        return List.copyOf(result.values());
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value == null ? "{}" : value);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_FLOW_JSON", exception.getMessage());
        }
    }

    private static String nodeName(JsonNode node) {
        String name = node.path("props").path("name").asText(null);
        if (name == null || name.isBlank()) name = node.path("props").path("title").asText(null);
        if (name == null || name.isBlank()) name = node.path("name").asText(null);
        return name == null || name.isBlank() ? node.path("id").asText() : name;
    }

    private static String userName(User user, long id) {
        if (user == null) return "用户#" + id;
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getUsername() == null || user.getUsername().isBlank()
            ? "用户#" + id : user.getUsername();
    }

    private record PreviewContext(JsonNode root, JsonNode data, long starterId, long formDefId,
                                  Map<String, List<Long>> selfSelected,
                                  ProcessInstance reworkInstance) {
    }

    private record PreviewNode(JsonNode node, boolean deferred,
                               List<WorkflowRuntimeV2.Assignment> assignments) {
    }
}
