package com.antflow.process;

import com.antflow.automation.WebhookSecurityPolicy;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ProcessDefinitionService {
    private static final int MAX_TREE_DEPTH = 50;
    /** 附件/检查项等复杂字段在 v1 不支持审批节点编辑，只能隐藏或只读。 */
    private static final Set<String> EDITABLE_FORBIDDEN_TYPES = Set.of(
        "image_upload", "video_upload", "file_upload", "audio_upload", "location", "checklist");

    private final ProcessDefinitionMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    private final WebhookSecurityPolicy webhookSecurityPolicy;
    @Autowired(required = false)
    private DefinitionVersionRepository versions;

    public ProcessDefinitionService(ProcessDefinitionMapper mapper,
                                    FormDefinitionService formDefinitionService,
                                    ObjectMapper json) {
        this(mapper, formDefinitionService, json, null);
    }

    @Autowired
    public ProcessDefinitionService(ProcessDefinitionMapper mapper,
                                    FormDefinitionService formDefinitionService,
                                    ObjectMapper json,
                                    WebhookSecurityPolicy webhookSecurityPolicy) {
        this.mapper = mapper;
        this.formDefinitionService = formDefinitionService;
        this.json = json;
        this.webhookSecurityPolicy = webhookSecurityPolicy;
    }

    public ProcessDefinition getById(Long id) {
        return mapper.selectById(id);
    }

    @Transactional
    public ProcessDefinition saveOrUpdateDraft(Long id, Long formDefId, Object process,
                                                Long userId) {
        ProcessDefinition pd;
        if (id == null) {
            pd = mapper.selectOne(new QueryWrapper<ProcessDefinition>()
                .eq("form_def_id", formDefId)
                .orderByDesc("id")
                .last("LIMIT 1"));
            if (pd == null) {
                pd = new ProcessDefinition();
                pd.setFormDefId(formDefId);
                pd.setVersion(1);
                pd.setProcess(writeJson(process));
                pd.setStatus("DRAFT");
                pd.setCreatedBy(userId);
                mapper.insert(pd);
            } else {
                pd.setProcess(writeJson(process));
                pd.setStatus("DRAFT");
                mapper.updateById(pd);
            }
        } else {
            pd = mapper.selectById(id);
            if (pd == null) {
                throw new BizException("PROCESS_NOT_FOUND", "Process not found: " + id);
            }
            pd.setProcess(writeJson(process));
            pd.setStatus("DRAFT");
            mapper.updateById(pd);
        }
        return pd;
    }

    @Transactional
    public ProcessDefinition publish(Long id) {
        ProcessDefinition pd = mapper.selectById(id);

        var fd = formDefinitionService.getById(pd.getFormDefId());
        if (!"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FOR_FORM_NOT_PUBLISHED",
                "Associated form must be PUBLISHED before publishing the flow");
        }

        pd.setProcess(normalizeConditionValuesForPublish(pd.getProcess(), fd.getSchema()));
        validateProcessTree(pd.getProcess(),
            formDefinitionService.leafFieldTypes(fd.getSchema()));
        validateStarterReadonlyDefaults(pd.getProcess(), fd.getSchema());

        pd.setStatus("PUBLISHED");
        pd.setVersion(pd.getVersion() + 1);
        mapper.updateById(pd);
        if (versions != null) versions.publishProcess(pd, fd);
        return pd;
    }

    public ProcessDefinition latestPublishedForForm(Long formDefId) {
        ProcessDefinition published = versions == null ? null
            : versions.runtimeProcessForForm(formDefId);
        return published != null ? published : mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId).eq("status", "PUBLISHED")
            .orderByDesc("version").last("LIMIT 1"));
    }

    public ProcessDefinition findByForm(Long formDefId) {
        return mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId)
            .orderByDesc("id").last("LIMIT 1"));
    }

    public List<ProcessDefinition> list() {
        return mapper.selectList(null);
    }

    /** 将选项条件严格规范化为 Schema 中的真实 value；兼容唯一精确匹配的旧 label。 */
    public String normalizeConditionValues(String processJson, String formSchema) {
        return normalizeConditionValues(processJson, formSchema, false);
    }

    String normalizeConditionValuesForPublish(String processJson, String formSchema) {
        return normalizeConditionValues(processJson, formSchema, true);
    }

    private String normalizeConditionValues(String processJson, String formSchema,
                                            boolean reconcileReferences) {
        try {
            JsonNode root = json.readTree(processJson == null ? "{}" : processJson);
            JsonNode schema = json.readTree(formSchema == null ? "[]" : formSchema);
            Map<String, OptionField> fields = new LinkedHashMap<>();
            Set<String> formFieldIds = new HashSet<>();
            Set<String> forbiddenConditionFields = new HashSet<>();
            collectOptionFields(schema, fields, formFieldIds, forbiddenConditionFields);
            normalizeConditions(root, fields, formFieldIds, forbiddenConditionFields,
                reconcileReferences, 1);
            return json.writeValueAsString(root);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("BAD_FLOW", "条件值规范化失败: " + e.getMessage());
        }
    }

    private void collectOptionFields(JsonNode nodes, Map<String, OptionField> fields,
                                     Set<String> formFieldIds,
                                     Set<String> forbiddenConditionFields) {
        if (!nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText();
            String fieldId = node.path("id").asText();
            if (!fieldId.isBlank()
                && !Set.of("span_layout", "table_list", "description").contains(type)) {
                formFieldIds.add(fieldId);
            }
            if (Set.of("select", "radio", "multi_select", "checkbox").contains(type)) {
                List<JsonNode> options = new ArrayList<>();
                node.path("props").path("options").forEach(options::add);
                fields.put(fieldId, new OptionField(type, options));
            }
            if (Set.of("audio_upload", "location").contains(type)) {
                forbiddenConditionFields.add(fieldId);
            }
            collectOptionFields(node.path("children"), fields, formFieldIds,
                forbiddenConditionFields);
        }
    }

    private void normalizeConditions(JsonNode node, Map<String, OptionField> fields,
                                     Set<String> formFieldIds,
                                     Set<String> forbiddenConditionFields,
                                     boolean reconcileReferences,
                                     int depth) {
        if (node == null || node.isNull() || !node.has("id")) return;
        ensureTreeDepth(node, depth);
        if (reconcileReferences) pruneMissingFormPerms(node, formFieldIds);
        if (isGateway(node)) {
            for (JsonNode branch : node.path("branchs")) {
                ensureTreeDepth(branch, depth + 1);
                normalizeBranchConditions(branch, fields, formFieldIds,
                    forbiddenConditionFields, reconcileReferences);
                normalizeConditions(branch.path("children"), fields, formFieldIds,
                    forbiddenConditionFields, reconcileReferences, depth + 2);
            }
        }
        normalizeConditions(node.path("children"), fields, formFieldIds,
            forbiddenConditionFields, reconcileReferences, depth + 1);
    }

    private void pruneMissingFormPerms(JsonNode node, Set<String> formFieldIds) {
        JsonNode props = node.path("props");
        JsonNode perms = props.path("formPerms");
        if (!("APPROVAL".equals(node.path("type").asText())
            || "ROOT".equals(node.path("type").asText()))
            || !(props instanceof ObjectNode propsObject) || !perms.isArray()) {
            return;
        }
        var kept = json.createArrayNode();
        perms.forEach(entry -> {
            String fieldId = entry.path("fieldId").asText("").trim();
            if (fieldId.isBlank() || formFieldIds.contains(fieldId)) {
                kept.add(entry.deepCopy());
            }
        });
        propsObject.set("formPerms", kept);
    }

    private boolean isGateway(JsonNode node) {
        String type = node.path("type").asText();
        return "CONDITIONS".equals(type) || "PARALLEL".equals(type);
    }

    private void normalizeBranchConditions(JsonNode branch, Map<String, OptionField> fields,
                                           Set<String> formFieldIds,
                                           Set<String> forbiddenConditionFields,
                                           boolean reconcileReferences) {
        for (JsonNode group : branch.path("props").path("groups")) {
            for (JsonNode condition : group.path("conditions")) {
                String fieldId = condition.path("field").asText();
                if (fieldId.isBlank()) continue;
                if (reconcileReferences && !formFieldIds.contains(fieldId)) {
                    throw new BizException("BAD_FLOW", "条件分支 "
                        + branch.path("id").asText() + " 引用了已删除的表单字段 " + fieldId);
                }
                if (forbiddenConditionFields.contains(fieldId)) {
                    throw new BizException("BAD_FLOW", "录音和定位字段不能作为流程条件");
                }
                OptionField field = fields.get(fieldId);
                if (field == null) continue;
                String operator = condition.path("operator").asText();
                boolean multiple = Set.of("multi_select", "checkbox").contains(field.type());
                if (multiple && !"contains".equals(operator)) {
                    throw new BizException("BAD_FLOW", "多选字段条件仅支持包含");
                }
                if (!multiple && !Set.of("==", "!=", "in").contains(operator)) {
                    throw new BizException("BAD_FLOW", "单选字段条件仅支持等于、不等于、包含于");
                }
                JsonNode value = condition.path("value");
                if ("in".equals(operator)) {
                    if (!value.isArray() || value.isEmpty()) {
                        throw new BizException("BAD_FLOW", "包含于条件必须选择至少一个选项");
                    }
                    var normalized = json.createArrayNode();
                    value.forEach(item -> normalized.add(resolveOptionValue(field, item)));
                    ((ObjectNode) condition).set("value", normalized);
                } else {
                    ((ObjectNode) condition).set("value", resolveOptionValue(field, value));
                }
            }
        }
    }

    private JsonNode resolveOptionValue(OptionField field, JsonNode configured) {
        List<JsonNode> labelMatches = new ArrayList<>();
        for (JsonNode option : field.options()) {
            JsonNode value = option.path("value");
            boolean sameValue = configured.equals(value);
            boolean sameLabel = configured.isTextual()
                && configured.asText().equals(option.path("label").asText());
            if (option.path("isOther").asBoolean(false)) {
                if (sameValue || sameLabel) {
                    throw new BizException("BAD_FLOW", "动态“其他”选项不能作为流程条件");
                }
                continue;
            }
            if (sameValue) return value.deepCopy();
            if (sameLabel) labelMatches.add(value);
        }
        if (labelMatches.size() == 1) return labelMatches.get(0).deepCopy();
        if (labelMatches.size() > 1) {
            throw new BizException("BAD_FLOW", "条件选项标签重复，无法确定真实值: "
                + configured.asText());
        }
        throw new BizException("BAD_FLOW", "条件选项不存在: " + configured.asText());
    }

    private record OptionField(String type, List<JsonNode> options) { }

    public List<ProcessDefinition> listAuthorized(long userId, boolean admin) {
        if (admin) return list();
        return mapper.selectList(new QueryWrapper<ProcessDefinition>().inSql("form_def_id",
            "SELECT grant_row.form_def_id FROM t_form_resource_grant grant_row "
                + "WHERE (grant_row.subject_type = 'USER' AND grant_row.subject_id = " + userId + ") "
                + "OR (grant_row.subject_type = 'ROLE' AND grant_row.subject_id IN "
                + "(SELECT ur.role_id FROM t_user_role ur JOIN t_role role ON role.id = ur.role_id "
                + "WHERE ur.user_id = " + userId + " AND role.enabled = true))"));
    }

    @Transactional
    public void deleteByForm(Long formDefId) {
        mapper.delete(new QueryWrapper<ProcessDefinition>().eq("form_def_id", formDefId));
    }

    /** Tree validation: ROOT is the unique root; APPROVAL has assignees configured;
     *  CONDITIONS has at least 1 branch including a default branch; node type is known. */
    void validateProcessTree(String processJson) {
        validateProcessTree(processJson, Map.of());
    }

    /** 带表单字段表校验流程树（节点级字段权限需要知道字段是否存在于当前表单）。 */
    void validateProcessTree(String processJson, Map<String, String> fieldTypes) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                json.readTree(processJson == null ? "{}" : processJson);
            if (!"ROOT".equals(root.path("type").asText())) {
                throw new BizException("BAD_FLOW", "流程必须以 ROOT 节点开始");
            }
            if (!walk(root, new HashSet<>(), fieldTypes, 1)) {
                throw new BizException("BAD_FLOW", "审批流程至少需要 1 个审批节点");
            }
            validateRootSettings(root);
            validateFormPerms(root, fieldTypes, true);
            validateRejectTargets(root, root);
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private boolean walk(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids,
                         Map<String, String> fieldTypes, int depth) {
        if (n == null || n.isNull() || !n.has("id")) return false;
        ensureTreeDepth(n, depth);
        registerId(n, ids);
        boolean hasApprovalNode = false;
        String type = n.path("type").asText();
        switch (type) {
            case "ROOT", "EMPTY" -> {}
            case "CC" -> validateCc(n);
            case "APPROVAL" -> {
                validateApproval(n, fieldTypes);
                hasApprovalNode = true;
            }
            case "DELAY" -> validateDelay(n);
            case "TRIGGER" -> validateTrigger(n, fieldTypes);
            case "CONDITIONS" -> {
                com.fasterxml.jackson.databind.JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 1) {
                    throw new BizException("BAD_FLOW", "条件分支至少需要 1 个分支");
                }
                int defaultCount = 0;
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (!"CONDITION".equals(b.path("type").asText())) {
                        throw new BizException("BAD_FLOW", "条件节点包含非法分支类型");
                    }
                    registerId(b, ids);
                    ensureTreeDepth(b, depth + 1);
                    if (b.path("props").path("isDefault").asBoolean(false)) {
                        defaultCount++;
                    } else {
                        validateCondition(b);
                    }
                    hasApprovalNode = walk(b.path("children"), ids, fieldTypes, depth + 2)
                        || hasApprovalNode;
                }
                if (defaultCount != 1) {
                    throw new BizException("BAD_FLOW", "条件分支必须且只能包含一个默认分支");
                }
            }
            case "CONDITION" -> {}
            case "PARALLEL" -> {
                String joinMode = n.path("props").path("joinMode").asText("ALL");
                if (!Set.of("ALL", "ANY", "AND", "OR").contains(joinMode)) {
                    throw new BizException("BAD_FLOW", "并行网关汇聚方式必须是 ALL 或 ANY");
                }
                com.fasterxml.jackson.databind.JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 2) {
                    throw new BizException("BAD_FLOW", "并行网关至少需要 2 个分支");
                }
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (!"BRANCH".equals(b.path("type").asText())) {
                        throw new BizException("BAD_FLOW", "并行节点包含非法分支类型");
                    }
                    registerId(b, ids);
                    ensureTreeDepth(b, depth + 1);
                    String conditionMode = b.path("props").path("conditionMode")
                        .asText(null);
                    if ("ALWAYS".equals(conditionMode) || conditionMode == null) {
                        // Unconditional parallel branches are the current format;
                        // missing mode remains compatible with older snapshots.
                    } else if ("WHEN_MATCHED".equals(conditionMode)) {
                        // Drafts created by the short-lived conditional-parallel UI
                        // used WHEN_MATCHED with an empty groups array. Treat that
                        // shape as the current unconditional branch format.
                        var groups = b.path("props").path("groups");
                        boolean emptyGroups = groups.isArray() &&
                            groups.elements().hasNext() &&
                            java.util.stream.StreamSupport.stream(
                                java.util.Spliterators.spliteratorUnknownSize(
                                    groups.elements(), 0), false)
                                .allMatch(group -> group.path("conditions").isArray()
                                    && group.path("conditions").isEmpty());
                        if (!(groups.isArray() && (groups.isEmpty() || emptyGroups))) {
                            throw new BizException("BAD_FLOW", "并行分支执行方式无效");
                        }
                    } else {
                        throw new BizException("BAD_FLOW", "并行分支执行方式无效");
                    }
                    com.fasterxml.jackson.databind.JsonNode inner = b.path("children");
                    if (inner == null || inner.isNull() || !inner.has("id")) {
                        throw new BizException("BAD_FLOW", "并行分支不能为空");
                    }
                    hasApprovalNode = walkParallelBranch(inner, ids, fieldTypes, depth + 2)
                        || hasApprovalNode;
                }
                if (n.path("children") == null || n.path("children").isNull()
                        || !n.path("children").has("id")) {
                    throw new BizException("BAD_FLOW", "并行网关必须配置汇聚后的后继节点");
                }
            }
            default -> throw new BizException("BAD_NODE_TYPE", "未知节点类型: " + type);
        }
        return walk(n.path("children"), ids, fieldTypes, depth + 1) || hasApprovalNode;
    }

    /** 并行分支沿完整流程树递归校验，允许嵌套分支和自动化节点。 */
    private boolean walkParallelBranch(com.fasterxml.jackson.databind.JsonNode n,
                                       Set<String> ids, Map<String, String> fieldTypes,
                                       int depth) {
        return walk(n, ids, fieldTypes, depth);
    }

    private void ensureTreeDepth(com.fasterxml.jackson.databind.JsonNode node, int depth) {
        if (node != null && !node.isNull() && node.has("id") && depth > MAX_TREE_DEPTH) {
            throw new BizException("BAD_FLOW", "流程树深度不能超过 " + MAX_TREE_DEPTH + " 层");
        }
    }

    private void registerId(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids) {
        String id = n.path("id").asText();
        if (id.isBlank() || !ids.add(id)) {
            throw new BizException("BAD_FLOW", "流程节点 ID 为空或重复: " + id);
        }
    }

    private void validateCc(com.fasterxml.jackson.databind.JsonNode n) {
        if (!n.path("props").path("assignedUser").isArray()
            || n.path("props").path("assignedUser").isEmpty()) {
            throw new BizException("BAD_FLOW", "抄送节点 " + n.path("id").asText() + " 未配置抄送人");
        }
    }

    private void validateCondition(com.fasterxml.jackson.databind.JsonNode n) {
        var groups = n.path("props").path("groups");
        if (!groups.isArray() || groups.isEmpty()) {
            throw new BizException("BAD_FLOW", "条件分支 " + n.path("id").asText() + " 未配置条件");
        }
        Set<String> operators = Set.of("==", "!=", ">", ">=", "<", "<=", "in", "contains");
        for (var group : groups) {
            var conditions = group.path("conditions");
            if (!conditions.isArray() || conditions.isEmpty()) {
                throw new BizException("BAD_FLOW", "条件分支包含空条件组");
            }
            for (var condition : conditions) {
                String operator = condition.path("operator").asText();
                if (condition.path("field").asText().isBlank()
                    || !operators.contains(operator)
                    || !condition.has("value")
                    || !validConditionValue(operator, condition.path("value"))) {
                    throw new BizException("BAD_FLOW", "条件表达式配置不完整");
                }
            }
        }
    }

    private boolean validConditionValue(String operator,
                                        com.fasterxml.jackson.databind.JsonNode value) {
        if ("in".equals(operator)) {
            if (!value.isArray() || value.isEmpty()) return false;
            for (var item : value) {
                if ((!item.isValueNode() && !item.isNull()) || item.asText().isBlank()) {
                    return false;
                }
            }
            return true;
        }
        return value.isValueNode() && !value.isNull() && !value.asText().isBlank();
    }

    private void validateDelay(com.fasterxml.jackson.databind.JsonNode n) {
        var props = n.path("props");
        if ("UNTIL_TIME".equals(props.path("mode").asText())) {
            try {
                java.time.LocalTime.parse(props.path("time").asText());
            } catch (Exception e) {
                throw new BizException("BAD_FLOW", "延时节点的当天时刻无效");
            }
            return;
        }
        long amount = props.path("amount").asLong(0);
        Duration duration = switch (props.path("unit").asText()) {
            case "MINUTES" -> Duration.ofMinutes(amount);
            case "HOURS" -> Duration.ofHours(amount);
            case "DAYS" -> Duration.ofDays(amount);
            default -> Duration.ZERO;
        };
        if (amount <= 0 || duration.isZero() || duration.compareTo(Duration.ofDays(365)) > 0) {
            throw new BizException("BAD_FLOW", "固定延时必须大于 0 且不超过 365 天");
        }
    }

    private void validateTrigger(com.fasterxml.jackson.databind.JsonNode n,
                                 Map<String, String> fieldTypes) {
        var props = n.path("props");
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE")
            .contains(props.path("method").asText())) {
            throw new BizException("BAD_FLOW", "Webhook 请求方法无效");
        }
        if (!Set.of("application/json", "application/x-www-form-urlencoded")
            .contains(props.path("contentType").asText())) {
            throw new BizException("BAD_FLOW", "Webhook 内容类型无效");
        }
        if (!Set.of("ON_SUCCESS", "AFTER_SEND").contains(props.path("continueMode").asText())
            || props.path("secret").asText().length() < 8) {
            throw new BizException("BAD_FLOW", "Webhook 继续方式或 HMAC 密钥无效");
        }
        URI uri;
        try {
            uri = URI.create(props.path("url").asText());
        } catch (Exception e) {
            throw new BizException("BAD_FLOW", "Webhook URL 无效");
        }
        if (webhookSecurityPolicy != null) webhookSecurityPolicy.validateDefinition(uri);

        Set<String> forbiddenHeaders = Set.of(
            "host", "content-length", "content-type", "authorization",
            "x-antflow-delivery-id", "x-antflow-signature"
        );
        for (var header : props.path("headers")) {
            String key = header.path("key").asText().trim();
            if (key.isBlank() || header.path("value").asText().isBlank()
                || forbiddenHeaders.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                throw new BizException("BAD_FLOW", "Webhook 请求头配置无效");
            }
        }
        for (var parameter : props.path("parameters")) {
            String source = parameter.path("source").asText();
            String fieldId = parameter.path("fieldId").asText();
            if (parameter.path("key").asText().isBlank()
                || !("FIXED".equals(source) || "FIELD".equals(source))
                || ("FIELD".equals(source) && fieldId.isBlank())
                || ("FIXED".equals(source) && !parameter.has("value"))) {
                throw new BizException("BAD_FLOW", "Webhook 请求参数配置无效");
            }
            if ("FIELD".equals(source) && !fieldTypes.containsKey(fieldId)) {
                throw new BizException("BAD_FLOW", "Webhook 节点 "
                    + n.path("id").asText() + " 的参数 "
                    + parameter.path("key").asText() + " 引用了已删除的表单字段 " + fieldId);
            }
        }
    }
    private void validateApproval(com.fasterxml.jackson.databind.JsonNode n,
                                  Map<String, String> fieldTypes) {
        com.fasterxml.jackson.databind.JsonNode p = n.path("props");
        String at = p.path("assignedType").asText();
        boolean empty = switch (at) {
            case "ASSIGN_USER" -> p.path("assignedUser").size() == 0;
            case "ROLE" -> p.path("role").size() == 0;
            case "FIELD_USER" -> p.path("fieldUser").path("fieldId").asText().isBlank();
            case "LEADER", "DIRECT_MANAGER", "SELF", "SELF_SELECT" -> false;
            default -> true;
        };
        if (empty) throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText() + " 未配置审批人");
        if ("DIRECT_MANAGER".equals(at)) {
            int level = p.path("manager").path("level").asInt(0);
            if (level < 1 || level > 10) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 的直属上级层级必须为 1 到 10");
            }
        }
        if ("FIELD_USER".equals(at)) {
            String fieldId = p.path("fieldUser").path("fieldId").asText();
            if (!Set.of("user_picker").contains(fieldTypes.get(fieldId))) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 的表单审批人字段必须是人员选择字段");
            }
        }
        if ("SELF_SELECT".equals(at)) {
            var selfSelect = p.get("selfSelect");
            if (selfSelect != null && !selfSelect.isNull()) {
                if (!selfSelect.isObject()) {
                    throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                        + " 的自选方式配置无效");
                }
                var multiple = selfSelect.get("multiple");
                if (multiple != null && !multiple.isBoolean()) {
                    throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                        + " 的 multiple 必须是布尔值");
                }
            }
        }
        String mode = p.path("mode").asText("ANY");
        if (!Set.of("OR", "AND", "ANY", "ALL", "RATIO").contains(mode)) {
            throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                + " 的多人审批方式无效");
        }
        if ("RATIO".equals(mode)) {
            int ratio = p.path("ratio").asInt(p.path("passRatio").asInt(0));
            if (ratio < 1 || ratio > 100) {
                throw new BizException("BAD_FLOW", "比例签通过比例必须为 1 到 100");
            }
        }
        validateTimeout(n);
        validateFormPerms(n, fieldTypes, false);
        validateCommentPresets(n);
    }

    private void validateTimeout(com.fasterxml.jackson.databind.JsonNode node) {
        var policy = node.path("props").path("timeoutPolicy");
        if (policy.isMissingNode() || policy.isNull()) return;
        long minutes = policy.path("afterMinutes").asLong(0);
        String action = policy.path("action").asText();
        if (!policy.isObject() || minutes < 1 || minutes > 525_600
            || !Set.of("REMIND", "ESCALATE", "AUTO_APPROVE").contains(action)) {
            throw new BizException("BAD_FLOW", "审批节点超时策略无效");
        }
        if ("AUTO_APPROVE".equals(action)
            && !"LOW".equals(policy.path("riskLevel").asText())) {
            throw new BizException("BAD_FLOW", "仅低风险流程允许超时自动通过");
        }
    }

    private void validateRootSettings(com.fasterxml.jackson.databind.JsonNode root) {
        var settings = root.path("props").path("settings");
        if (!settings.isObject()) settings = root.path("props");
        String strategy = settings.path("resubmitStrategy").asText("FULL");
        if (!Set.of("FULL", "DIFF_CONTINUE").contains(strategy)) {
            throw new BizException("BAD_FLOW", "重提策略必须是 FULL 或 DIFF_CONTINUE");
        }
        var fallback = settings.path("fallbackAssignee");
        if (!fallback.isMissingNode() && !fallback.isNull()
            && (!fallback.isObject()
                || !Set.of("USER", "ROLE").contains(fallback.path("type").asText())
                || !fallback.path("ids").isArray() || fallback.path("ids").isEmpty())) {
            throw new BizException("BAD_FLOW", "兜底审批人必须配置用户或角色");
        }
    }

    private void validateRejectTargets(com.fasterxml.jackson.databind.JsonNode root,
                                       com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || !node.has("id")) return;
        var targets = node.path("props").path("rejectTargets");
        if (!targets.isMissingNode() && !targets.isNull()) {
            if (!targets.isArray()) throw new BizException("BAD_FLOW", "驳回目标必须是数组");
            for (var value : targets) {
                String targetId = value.asText();
                var target = com.antflow.engine.tree.ProcessTreeNav.findById(root, targetId);
                if (target == null || !"APPROVAL".equals(target.path("type").asText())
                    || !com.antflow.engine.tree.ProcessTreeNav.isAncestor(
                        root, targetId, node.path("id").asText())) {
                    throw new BizException("BAD_FLOW", "驳回目标必须是当前节点的上游审批节点");
                }
            }
        }
        if (node.has("branchs")) {
            node.path("branchs").forEach(branch -> validateRejectTargets(root, branch));
        }
        validateRejectTargets(root, node.path("children"));
    }

    /** 校验节点级字段权限：字段必须存在于表单、模式合法、无重复；附件/检查项不可编辑。 */
    private void validateFormPerms(com.fasterxml.jackson.databind.JsonNode n,
                                   Map<String, String> fieldTypes,
                                   boolean allowComplexEditable) {
        com.fasterxml.jackson.databind.JsonNode perms = n.path("props").path("formPerms");
        if (perms.isMissingNode() || perms.isNull()) {
            return;
        }
        if (!perms.isArray()) {
            throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                + " 的 formPerms 必须是数组");
        }
        Set<String> seen = new HashSet<>();
        for (var entry : perms) {
            String fieldId = entry.path("fieldId").asText("").trim();
            String mode = entry.path("mode").asText("");
            if (fieldId.isBlank()) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 存在缺少字段 id 的权限配置");
            }
            if (!Set.of("HIDDEN", "READONLY", "EDITABLE").contains(mode)) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + " 的权限模式非法: " + mode);
            }
            if (!seen.add(fieldId)) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + " 重复配置权限");
            }
            String type = fieldTypes.get(fieldId);
            if (type == null) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + " 不存在于当前表单");
            }
            if (!allowComplexEditable && "EDITABLE".equals(mode)
                && EDITABLE_FORBIDDEN_TYPES.contains(type)) {
                throw new BizException("BAD_FLOW", nodeLabel(n) + " " + n.path("id").asText()
                    + " 字段 " + fieldId + "（" + type + "）暂不支持编辑");
            }
        }
    }

    private void validateCommentPresets(com.fasterxml.jackson.databind.JsonNode node) {
        var presets = node.path("props").path("commentPresets");
        if (presets.isMissingNode() || presets.isNull()) return;
        if (!presets.isObject()) {
            throw new BizException("BAD_FLOW", "审批意见预设必须是对象");
        }
        validateCommentPresetList(presets.path("approve"), "同意");
        validateCommentPresetList(presets.path("reject"), "驳回");
    }

    private void validateCommentPresetList(com.fasterxml.jackson.databind.JsonNode values,
                                           String action) {
        if (values.isMissingNode() || values.isNull()) return;
        if (!values.isArray() || values.size() > 10) {
            throw new BizException("BAD_FLOW", action + "意见预设最多 10 条");
        }
        Set<String> seen = new HashSet<>();
        for (var value : values) {
            String normalized = value.isTextual() ? value.asText().trim() : "";
            if (normalized.isBlank() || normalized.length() > 100) {
                throw new BizException("BAD_FLOW", action + "意见预设须为 1 到 100 字");
            }
            if (!seen.add(normalized)) {
                throw new BizException("BAD_FLOW", action + "意见预设不能重复");
            }
        }
    }

    public ApprovalCommentPresets commentPresets(Object process, String nodeId) {
        if (process == null || nodeId == null || nodeId.isBlank()) {
            return ApprovalCommentPresets.empty();
        }
        try {
            var root = process instanceof com.fasterxml.jackson.databind.JsonNode node
                ? node : process instanceof String string
                    ? json.readTree(string) : json.valueToTree(process);
            var current = com.antflow.engine.tree.ProcessTreeNav.findById(root, nodeId);
            if (current == null) return ApprovalCommentPresets.empty();
            var presets = current.path("props").path("commentPresets");
            return new ApprovalCommentPresets(
                normalizedPresets(presets.path("approve")),
                normalizedPresets(presets.path("reject")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("BAD_FLOW_JSON", error.getMessage());
        }
    }

    private List<String> normalizedPresets(com.fasterxml.jackson.databind.JsonNode values) {
        if (!values.isArray()) return List.of();
        var result = new java.util.LinkedHashSet<String>();
        for (var value : values) {
            String normalized = value.isTextual() ? value.asText().trim() : "";
            if (!normalized.isBlank() && normalized.length() <= 100 && result.size() < 10) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private void validateStarterReadonlyDefaults(String processJson, String schemaJson) {
        try {
            var root = json.readTree(processJson);
            var schema = json.readTree(schemaJson);
            for (var permission : root.path("props").path("formPerms")) {
                if (!"READONLY".equals(permission.path("mode").asText())) continue;
                String fieldId = permission.path("fieldId").asText();
                var field = findSchemaField(schema, fieldId);
                if (field == null) continue;
                boolean required = field.path("rules").path("required").asBoolean(
                    field.path("props").path("required").asBoolean(false));
                var defaultValue = field.path("props").path("defaultValue");
                if (required && (defaultValue.isMissingNode() || defaultValue.isNull()
                    || (defaultValue.isTextual() && defaultValue.asText().isBlank())
                    || (defaultValue.isArray() && defaultValue.isEmpty()))) {
                    throw new BizException("BAD_FLOW", "发起人只读必填字段 " + fieldId
                        + " 必须配置默认值");
                }
            }
        } catch (BizException error) {
            throw error;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("BAD_FLOW_JSON", error.getMessage());
        }
    }

    private com.fasterxml.jackson.databind.JsonNode findSchemaField(
        com.fasterxml.jackson.databind.JsonNode nodes, String fieldId) {
        if (!nodes.isArray()) return null;
        for (var node : nodes) {
            if (fieldId.equals(node.path("id").asText())) return node;
            var nested = findSchemaField(node.path("children"), fieldId);
            if (nested != null) return nested;
        }
        return null;
    }

    private String nodeLabel(com.fasterxml.jackson.databind.JsonNode node) {
        return "ROOT".equals(node.path("type").asText()) ? "发起人节点" : "审批节点";
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
