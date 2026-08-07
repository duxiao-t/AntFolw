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
import java.util.Set;

@Service
public class ProcessDefinitionService {
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

        validateProcessTree(pd.getProcess());

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

    @Transactional
    public void deleteByForm(Long formDefId) {
        mapper.delete(new QueryWrapper<ProcessDefinition>().eq("form_def_id", formDefId));
    }

    /** Tree validation: ROOT is the unique root; APPROVAL has assignees configured;
     *  CONDITIONS has at least 1 branch including a default branch; node type is known. */
    void validateProcessTree(String processJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                json.readTree(processJson == null ? "{}" : processJson);
            if (!"ROOT".equals(root.path("type").asText())) {
                throw new BizException("BAD_FLOW", "流程必须以 ROOT 节点开始");
            }
            if (!walk(root, new HashSet<>())) {
                throw new BizException("BAD_FLOW", "审批流程至少需要 1 个审批节点");
            }
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private boolean walk(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids) {
        if (n == null || n.isNull() || !n.has("id")) return false;
        registerId(n, ids);
        boolean hasApprovalNode = false;
        String type = n.path("type").asText();
        switch (type) {
            case "ROOT", "EMPTY" -> {}
            case "CC" -> validateCc(n);
            case "APPROVAL" -> {
                validateApproval(n);
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
                    hasApprovalNode = walk(b.path("children"), ids) || hasApprovalNode;
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
                int alwaysBranchCount = 0;
                for (com.fasterxml.jackson.databind.JsonNode b : branchs) {
                    if (!"BRANCH".equals(b.path("type").asText())) {
                        throw new BizException("BAD_FLOW", "并行节点包含非法分支类型");
                    }
                    registerId(b, ids);
                    String conditionMode = b.path("props").path("conditionMode")
                        .asText("ALWAYS");
                    if ("ALWAYS".equals(conditionMode)) {
                        alwaysBranchCount++;
                    } else if ("WHEN_MATCHED".equals(conditionMode)) {
                        validateCondition(b);
                    } else {
                        throw new BizException("BAD_FLOW", "并行分支执行方式无效");
                    }
                    com.fasterxml.jackson.databind.JsonNode inner = b.path("children");
                    if (inner == null || inner.isNull() || !inner.has("id")) {
                        throw new BizException("BAD_FLOW", "并行分支不能为空");
                    }
                    hasApprovalNode = walkParallelBranch(inner, ids) || hasApprovalNode;
                }
                if (alwaysBranchCount == 0) {
                    throw new BizException("BAD_FLOW", "并行网关至少需要一个始终执行的分支");
                }
                if (n.path("children") == null || n.path("children").isNull()
                        || !n.path("children").has("id")) {
                    throw new BizException("BAD_FLOW", "并行网关必须配置汇聚后的后继节点");
                }
            }
            default -> throw new BizException("BAD_NODE_TYPE", "未知节点类型: " + type);
        }
        return walk(n.path("children"), ids) || hasApprovalNode;
    }

    /** 并行分支内校验：单链 APPROVAL/CC/EMPTY，不允许嵌套 CONDITIONS/PARALLEL。 */
    private boolean walkParallelBranch(com.fasterxml.jackson.databind.JsonNode n, Set<String> ids) {
        if (n == null || n.isNull() || !n.has("id")) return false;
        registerId(n, ids);
        boolean hasApprovalNode = false;
        String type = n.path("type").asText();
        switch (type) {
            case "CC" -> validateCc(n);
            case "APPROVAL" -> {
                validateApproval(n);
                hasApprovalNode = true;
            }
            default -> throw new BizException("BAD_FLOW", "并行分支内只允许审批/抄送/空节点: " + type);
        }
        if (n.has("branchs") && n.path("branchs").isArray() && n.path("branchs").size() > 0) {
            throw new BizException("BAD_FLOW", "并行分支内不允许嵌套分支节点");
        }
        return walkParallelBranch(n.path("children"), ids) || hasApprovalNode;
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
    private void validateApproval(com.fasterxml.jackson.databind.JsonNode n) {
        com.fasterxml.jackson.databind.JsonNode p = n.path("props");
        String at = p.path("assignedType").asText();
        boolean empty = switch (at) {
            case "ASSIGN_USER" -> p.path("assignedUser").size() == 0;
            case "ROLE" -> p.path("role").size() == 0;
            case "LEADER", "SELF", "SELF_SELECT" -> false;
            default -> true;
        };
        if (empty) throw new BizException("BAD_FLOW", "审批节点 " + n.path("id").asText() + " 未配置审批人");
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}
