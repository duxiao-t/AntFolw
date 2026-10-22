package com.antflow.process;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程定义的 JSON 改写：条件分支取值规范化 + 旧版流程级兜底配置的迁移。
 *
 * <p>**为什么单独一类**：这些方法只读写流程树 JSON，不碰数据库、不碰事务，但"何时严格、何时宽松"
 * 是两套不同口径，混在门面里时很容易被当成同一件事：
 * <ul>
 *   <li>{@link #normalizeConditionValues} 是**运行时**口径——已发布快照里的字段可能早已从表单删除，
 *       必须原样放行，否则存量实例一审批就炸；</li>
 *   <li>{@link #normalizeConditionValuesForPublish} 是**发布时**口径——同一次操作里还要把
 *       指向已删字段的条件与 formPerms 一并清掉，否则新快照带着无效引用发布出去。</li>
 * </ul>
 * 两者只差一个 {@code reconcileReferences} 开关，拆成两个类会让"口径必须成对演进"这条约束消失。
 *
 * <p>**易错处**：动态"其他"选项（{@code isOther}）不能作为条件值；标签唯一精确匹配只用于兼容旧数据，
 * 多个 label 命中时必须报错而不是随便挑一个——挑错会让流程静默走错分支。
 */
class ProcessConditionNormalizer {
    private final ObjectMapper json;

    ProcessConditionNormalizer(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 发布前把流程级兜底改写成节点级要求，并清掉遗留的流程级兜底对象。
     *
     * <p>先写 {@code fallbackPolicy=NODE_REQUIRED} 再删 {@code fallbackAssignee}：顺序反了的话，
     * 中间态会被 {@code removeLegacyFallbackSettings} 的 root 分支再删一次，结果一样但意图不清。
     */
    String applyNodeFallbackPolicyForPublish(String processJson) {
        try {
            JsonNode parsed = json.readTree(processJson == null ? "{}" : processJson);
            if (!(parsed instanceof ObjectNode root) || !"ROOT".equals(root.path("type").asText())) {
                throw new BizException("BAD_FLOW", "流程必须以 ROOT 节点开始");
            }
            ObjectNode props = objectProps(root);
            props.put("fallbackPolicy", "NODE_REQUIRED");
            props.remove("fallbackAssignee");
            removeLegacyFallbackSettings(root, true);
            return json.writeValueAsString(root);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private ObjectNode objectProps(ObjectNode node) {
        JsonNode value = node.get("props");
        if (value instanceof ObjectNode props) return props;
        ObjectNode props = json.createObjectNode();
        node.set("props", props);
        return props;
    }

    private void removeLegacyFallbackSettings(JsonNode node, boolean root) {
        if (!(node instanceof ObjectNode object)) return;
        JsonNode props = object.get("props");
        if (props instanceof ObjectNode values) {
            if (root) {
                values.remove("fallbackAssignee");
                if (values.path("settings") instanceof ObjectNode settings) {
                    settings.remove("fallbackAssignee");
                }
            }
            if ("APPROVAL".equals(object.path("type").asText())) values.remove("nobody");
        }
        object.path("branchs").forEach(branch -> removeLegacyFallbackSettings(branch, false));
        removeLegacyFallbackSettings(object.path("children"), false);
    }

    /** 将选项条件严格规范化为 Schema 中的真实 value；兼容唯一精确匹配的旧 label。 */
    String normalizeConditionValues(String processJson, String formSchema) {
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
        ProcessTreeValidator.ensureTreeDepth(node, depth);
        if (reconcileReferences) pruneMissingFormPerms(node, formFieldIds);
        if (isGateway(node)) {
            for (JsonNode branch : node.path("branchs")) {
                ProcessTreeValidator.ensureTreeDepth(branch, depth + 1);
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
}
