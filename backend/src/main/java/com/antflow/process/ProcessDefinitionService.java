package com.antflow.process;

import com.antflow.automation.WebhookSecurityPolicy;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ProcessDefinitionService {
    /** 附件/检查项等复杂字段在 v1 不支持审批节点编辑，只能隐藏或只读。 */
    private static final Set<String> EDITABLE_FORBIDDEN_TYPES = Set.of(
        "image_upload", "video_upload", "file_upload", "checklist");

    private final ProcessDefinitionMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    private final WebhookSecurityPolicy webhookSecurityPolicy;

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

        validateProcessTree(pd.getProcess(),
            formDefinitionService.leafFieldTypes(fd.getSchema()));

        pd.setStatus("PUBLISHED");
        pd.setVersion(pd.getVersion() + 1);
        mapper.updateById(pd);
        return pd;
    }

    public ProcessDefinition latestPublishedForForm(Long formDefId) {
        return mapper.selectOne(new QueryWrapper<ProcessDefinition>()
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
            if (!walk(root, new HashSet<>(), fieldTypes)) {
                throw new BizException("BAD_FLOW", "审批流程至少需要 1 个审批节点");
            }
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private boolean walk(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids,
                         Map<String, String> fieldTypes) {
        if (n == null || n.isNull() || !n.has("id")) return false;
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
            case "TRIGGER" -> validateTrigger(n);
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
                    if (b.path("props").path("isDefault").asBoolean(false)) {
                        defaultCount++;
                    } else {
                        validateCondition(b);
                    }
                    hasApprovalNode = walk(b.path("children"), ids, fieldTypes)
                        || hasApprovalNode;
                }
                if (defaultCount != 1) {
                    throw new BizException("BAD_FLOW", "条件分支必须且只能包含一个默认分支");
                }
            }
            case "CONDITION" -> {}
            case "PARALLEL" -> {
                com.fasterxml.jackson.databind.JsonNode branchs = n.path("branchs");
                if (!branchs.isArray() || branchs.size() < 2) {
                    throw new BizException("BAD_FLOW", "并行网关至少需要 2 个分支");
                }
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (!"BRANCH".equals(b.path("type").asText())) {
                        throw new BizException("BAD_FLOW", "并行节点包含非法分支类型");
                    }
                    registerId(b, ids);
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
                    hasApprovalNode = walkParallelBranch(inner, ids, fieldTypes)
                        || hasApprovalNode;
                }
                if (n.path("children") == null || n.path("children").isNull()
                        || !n.path("children").has("id")) {
                    throw new BizException("BAD_FLOW", "并行网关必须配置汇聚后的后继节点");
                }
            }
            default -> throw new BizException("BAD_NODE_TYPE", "未知节点类型: " + type);
        }
        return walk(n.path("children"), ids, fieldTypes) || hasApprovalNode;
    }

    /** 并行分支沿完整流程树递归校验，允许嵌套分支和自动化节点。 */
    private boolean walkParallelBranch(com.fasterxml.jackson.databind.JsonNode n,
                                       Set<String> ids, Map<String, String> fieldTypes) {
        return walk(n, ids, fieldTypes);
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

    private void validateTrigger(com.fasterxml.jackson.databind.JsonNode n) {
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
            if (parameter.path("key").asText().isBlank()
                || !("FIXED".equals(source) || "FIELD".equals(source))
                || ("FIELD".equals(source) && parameter.path("fieldId").asText().isBlank())
                || ("FIXED".equals(source) && !parameter.has("value"))) {
                throw new BizException("BAD_FLOW", "Webhook 请求参数配置无效");
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
        validateFormPerms(n, fieldTypes);
    }

    /** 校验节点级字段权限：字段必须存在于表单、模式合法、无重复；附件/检查项不可编辑。 */
    private void validateFormPerms(com.fasterxml.jackson.databind.JsonNode n,
                                   Map<String, String> fieldTypes) {
        com.fasterxml.jackson.databind.JsonNode perms = n.path("props").path("formPerms");
        if (perms.isMissingNode() || perms.isNull()) {
            return;
        }
        if (!perms.isArray()) {
            throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                + " 的 formPerms 必须是数组");
        }
        Set<String> seen = new HashSet<>();
        for (var entry : perms) {
            String fieldId = entry.path("fieldId").asText("").trim();
            String mode = entry.path("mode").asText("");
            if (fieldId.isBlank()) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 存在缺少字段 id 的权限配置");
            }
            if (!Set.of("HIDDEN", "READONLY", "EDITABLE").contains(mode)) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 字段 " + fieldId + " 的权限模式非法: " + mode);
            }
            if (!seen.add(fieldId)) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 字段 " + fieldId + " 重复配置权限");
            }
            String type = fieldTypes.get(fieldId);
            if (type == null) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 字段 " + fieldId + " 不存在于当前表单");
            }
            if ("EDITABLE".equals(mode) && EDITABLE_FORBIDDEN_TYPES.contains(type)) {
                throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText()
                    + " 字段 " + fieldId + "（" + type + "）暂不支持编辑");
            }
        }
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
