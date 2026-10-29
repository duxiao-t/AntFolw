package com.antflow.process;

import com.antflow.automation.WebhookBodyTemplate;
import com.antflow.automation.WebhookSecurityPolicy;
import com.antflow.engine.BizException;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 单个节点的配置校验：抄送人、条件表达式、延时、Webhook、审批人。
 *
 * <p>与 {@link ProcessTreeValidator} 的分工：这里只回答"这个节点的字段填全了没有、取值合法吗"，
 * 不关心它在树里的位置。所以这些方法都只接收节点本身（加一张表单字段类型表）。
 *
 * <p>**为什么 Webhook 单独一段**：它要求 HMAC 密钥长度、禁止覆盖签名头、参数来源只能是
 * FIXED/FIELD，且 {@link WebhookSecurityPolicy} 还会追加出站地址限制——这些是安全边界，
 * 与"审批人填了没有"不是同一个变化原因。
 */
class ProcessNodeValidator {
    private final ProcessApprovalRules approvalRules;
    private final WebhookSecurityPolicy webhookSecurityPolicy;

    ProcessNodeValidator(ProcessApprovalRules approvalRules,
                         WebhookSecurityPolicy webhookSecurityPolicy) {
        this.approvalRules = approvalRules;
        this.webhookSecurityPolicy = webhookSecurityPolicy;
    }

    void validateCc(com.fasterxml.jackson.databind.JsonNode n,
                    Map<String, String> fieldTypes) {
        var props = n.path("props");
        String assignedType = props.path("assignedType").asText("ASSIGN_USER");
        boolean empty = switch (assignedType) {
            case "ASSIGN_USER" -> !props.path("assignedUser").isArray()
                || props.path("assignedUser").isEmpty();
            case "ROLE" -> !props.path("role").isArray() || props.path("role").isEmpty();
            case "FIELD_USER" -> props.path("fieldUser").path("fieldId").asText().isBlank();
            default -> true;
        };
        if (empty) {
            throw new BizException("BAD_FLOW", "抄送节点 " + n.path("id").asText()
                + " 未配置抄送人");
        }
        if ("FIELD_USER".equals(assignedType)) {
            String fieldId = props.path("fieldUser").path("fieldId").asText();
            if (!"user_picker".equals(fieldTypes.get(fieldId))) {
                throw new BizException("BAD_FLOW", "抄送节点 " + n.path("id").asText()
                    + " 的表单抄送人字段必须是人员选择字段");
            }
        }
    }

    void validateCondition(com.fasterxml.jackson.databind.JsonNode n) {
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

    void validateDelay(com.fasterxml.jackson.databind.JsonNode n) {
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

    void validateTrigger(com.fasterxml.jackson.databind.JsonNode n,
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
        String bodyMode = props.path("bodyMode").asText("PARAMETERS");
        if (!Set.of("PARAMETERS", "TEMPLATE").contains(bodyMode)) {
            throw new BizException("BAD_FLOW", "Webhook 请求体模式无效");
        }
        if ("TEMPLATE".equals(bodyMode)
            && !"application/json".equals(props.path("contentType").asText())) {
            throw new BizException("BAD_FLOW", "Webhook 模板模式仅支持 JSON 内容类型");
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
        if ("TEMPLATE".equals(bodyMode)) {
            validateBodyTemplate(n, props, fieldTypes);
        } else {
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
    }

    /**
     * 模板模式：{@code bodyTemplate} 是最终请求体（JSON），{@code ${字段id}} 占位符按**所在位置**渲染
     * （引号内按文本插入并转义、引号外按 JSON 值插入，见 {@link WebhookBodyTemplate}）。这里做两件事：
     * 占位符必须引用现存字段；把占位符按上下文替换为合法片段后应能解析为合法 JSON（结构校验）。
     */
    private void validateBodyTemplate(com.fasterxml.jackson.databind.JsonNode n,
                                      com.fasterxml.jackson.databind.JsonNode props,
                                      Map<String, String> fieldTypes) {
        String template = props.path("bodyTemplate").asText();
        if (template.isBlank()) {
            throw new BizException("BAD_FLOW", "Webhook 请求体模板不能为空");
        }
        for (String ref : WebhookBodyTemplate.references(template)) {
            if (!fieldTypes.containsKey(ref)) {
                throw new BizException("BAD_FLOW", "Webhook 节点 " + n.path("id").asText()
                    + " 的请求体模板引用了已删除的表单字段 " + ref);
            }
        }
        String probe = WebhookBodyTemplate.probe(template);
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(probe);
        } catch (Exception e) {
            throw new BizException("BAD_FLOW", "Webhook 请求体模板不是合法 JSON");
        }
    }

    void validateApproval(com.fasterxml.jackson.databind.JsonNode n,
                          Map<String, String> fieldTypes,
                          boolean nodeFallbackPolicy) {
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
        if (nodeFallbackPolicy) approvalRules.validateFallbackAssignee(n);
        approvalRules.validateTimeout(n);
        approvalRules.validateFormPerms(n, fieldTypes, false);
        approvalRules.validateCommentPresets(n);
    }
}
