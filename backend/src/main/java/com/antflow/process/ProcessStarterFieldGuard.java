package com.antflow.process;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * 发布前的"发起人视角"检查：只读必填字段必须带默认值、动态人员字段必须来自发起人可填的顶层字段。
 *
 * <p>**为什么只在这一层做**：这两条都要求流程树和表单 schema **同时**在手，而运行时（审批预览、
 * 引擎启动）拿的是已发布快照，schema 可能已经变了。把它们放进通用树校验会让校验器依赖表单服务，
 * 也让"运行时放行旧快照"这条兼容策略失效。
 *
 * <p>**易错处**：{@code table_list} 里的 {@code user_picker} 不算顶层字段——明细行的人员是逐行数据，
 * 不能当审批人；{@code span_layout} 是纯布局容器，必须继续下钻。判断"发起人是否可填"看的是
 * ROOT 的 formPerms，缺省视为 EDITABLE（老流程没有这条配置）。
 */
class ProcessStarterFieldGuard {
    private final ObjectMapper json;

    ProcessStarterFieldGuard(ObjectMapper json) {
        this.json = json;
    }

    void validateStarterReadonlyDefaults(String processJson, String schemaJson) {
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

    /** Dynamic personnel references are evaluated against starter data, never table rows. */
    void validateDynamicUserFields(String processJson, String schema) {
        try {
            JsonNode root = json.readTree(processJson == null ? "{}" : processJson);
            Set<String> topLevelUserFields = new HashSet<>();
            collectTopLevelUserFields(json.readTree(schema == null ? "[]" : schema),
                topLevelUserFields);
            validateDynamicUserFields(root, root, topLevelUserFields);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("BAD_FLOW", "表单人员字段校验失败: " + e.getMessage());
        }
    }

    private void collectTopLevelUserFields(JsonNode nodes, Set<String> fields) {
        if (!nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText();
            if ("table_list".equals(type)) continue;
            if ("span_layout".equals(type)) {
                collectTopLevelUserFields(node.path("children"), fields);
                continue;
            }
            if ("user_picker".equals(type) && !node.path("id").asText().isBlank()) {
                fields.add(node.path("id").asText());
            }
        }
    }

    private void validateDynamicUserFields(JsonNode node, JsonNode root,
                                           Set<String> topLevelUserFields) {
        if (node == null || node.isNull() || !node.has("id")) return;
        String nodeType = node.path("type").asText();
        String assignedType = node.path("props").path("assignedType").asText();
        if (("APPROVAL".equals(nodeType) || "CC".equals(nodeType))
            && "FIELD_USER".equals(assignedType)) {
            String fieldId = node.path("props").path("fieldUser").path("fieldId").asText();
            if (!topLevelUserFields.contains(fieldId)) {
                throw new BizException("BAD_FLOW", ("APPROVAL".equals(nodeType) ? "审批" : "抄送")
                    + "节点 " + node.path("id").asText()
                    + " 的人员字段必须是发起人可见的顶层人员选择字段");
            }
            if ("APPROVAL".equals(nodeType)) {
                if (!"EDITABLE".equals(starterFieldMode(root, fieldId))) {
                    throw new BizException("BAD_FLOW", "审批节点 " + node.path("id").asText()
                        + " 的表单审批人字段必须是发起人可填写的人员选择字段");
                }
            }
        }
        node.path("branchs").forEach(branch ->
            validateDynamicUserFields(branch, root, topLevelUserFields));
        validateDynamicUserFields(node.path("children"), root, topLevelUserFields);
    }

    private static String starterFieldMode(JsonNode root, String fieldId) {
        JsonNode permissions = root.path("props").path("formPerms");
        if (!permissions.isArray()) return "EDITABLE";
        for (JsonNode entry : permissions) {
            if (fieldId.equals(entry.path("fieldId").asText())) {
                return entry.path("mode").asText("EDITABLE");
            }
        }
        return "EDITABLE";
    }
}
