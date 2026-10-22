package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.antflow.form.options.OptionRuntimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 发布期的结构校验：这份 schema 配得对吗？
 *
 * <p>与 {@code FormSubmissionValidator} 的分工：这里只看 schema（无数据、无库、无事务），
 * 错了就是 {@code BAD_SCHEMA}；那边看 schema + 已填值，错了是 {@code FORM_DATA_INVALID}。
 * 混在一起时，一条"选项没配全"的报错会在提交路径上以"数据格式不正确"的形式出现，
 * 用户根本不知道该去改哪里。
 *
 * <p>三条不能顺手改的规则：
 * <ol>
 *   <li>**草稿保存不跑全量结构校验**，只拦 {@code displayCondition} 的环：存量草稿结构不完整是常态，
 *       全量校验会让它们连保存都保存不了。{@link #validateSchema} 只给发布用。</li>
 *   <li>**环检测必须覆盖 children**：嵌套容器里的子字段同样进依赖图，只看根节点会漏。</li>
 *   <li>**{@code optionSource: {}} 按未绑定处理**：那是"选了外部源还没选版本"的中途状态，
 *       当成外部绑定去校验会让表单"能发布、填不了"。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormSchemaValidator {

    private final FormSchemaParser schemaParser;
    private final OptionRuntimeService optionRuntimeService;
    private final ObjectMapper json;

    public void validateSchema(String s) {
        var nodes = schemaParser.parseSchema(s);
        rejectDisplayConditionCycles(nodes);
        nodes.forEach(this::validatePublishingNode);
    }

    /**
     * 拒绝 displayCondition 的环（互指 A↔B、自指 A→A）。
     *
     * <p>环是**配置错误**：环上字段到底显不显示没有可解释的答案。运行时不会崩（{@code resolveVisible}
     * 用 Java `Set.add` 的布尔返回值判环），但两端都会给出一个"谁也说不清对不对"的结果，所以保存与
     * 发布都拒绝。（前端 `visibleNodeIds` 曾经写成 `!visiting.add(id)`——JS 的 `Set.add` 返回集合本身，
     * 那个条件恒假、等于没判环，自指与互指会直接 RangeError，抛在 React 渲染里就是整页白屏。）
     *
     * <p>只查环，不在这里补别的结构校验：草稿保存历来不校验结构，改成全量校验会让存量草稿存不下去。
     */
    public void rejectDisplayConditionCycles(Object schema) {
        JsonNode root;
        try {
            root = json.valueToTree(schema);
        } catch (IllegalArgumentException e) {
            return; // 形状怪异的对象交给既有结构校验，这里只管环
        }
        if (root == null || !root.isArray()) return;
        var nodes = new java.util.ArrayList<JsonNode>();
        root.forEach(nodes::add);
        rejectDisplayConditionCycles(nodes);
    }

    public void rejectDisplayConditionCycles(List<JsonNode> roots) {
        var sourceOf = new java.util.LinkedHashMap<String, String>();
        roots.forEach(node -> collectConditionSources(node, sourceOf));
        var state = new java.util.HashMap<String, Integer>();
        for (String start : sourceOf.keySet()) {
            if (state.containsKey(start)) continue;
            var path = new java.util.ArrayList<String>();
            String id = start;
            while (id != null && !state.containsKey(id)) {
                state.put(id, 1);
                path.add(id);
                String next = sourceOf.get(id);
                id = next == null || next.isBlank() ? null : next;
            }
            if (id != null && state.get(id) == 1) {
                var cycle = new java.util.ArrayList<>(path.subList(path.indexOf(id), path.size()));
                cycle.add(id);
                throw new BizException("BAD_SCHEMA",
                    "displayCondition 互指: " + String.join(" → ", cycle) + "，请改掉引用");
            }
            path.forEach(visited -> state.put(visited, 2));
        }
    }

    private void collectConditionSources(JsonNode node, Map<String, String> out) {
        String id = node.path("id").asText("");
        if (!id.isBlank()) {
            var condition = node.path("props").path("displayCondition");
            out.putIfAbsent(id, condition.path("fieldId")
                .asText(condition.path("field").asText("")).trim());
        }
        node.path("children").forEach(child -> collectConditionSources(child, out));
    }

    private void validateSelectDisplayStyle(com.fasterxml.jackson.databind.JsonNode node) {
        var displayStyle = node.path("props").get("displayStyle");
        if (displayStyle != null && !displayStyle.isNull()
            && (!displayStyle.isTextual() || !Set.of(
                "list", "dropdown", "block_single", "block_double"
            ).contains(displayStyle.asText()))) {
            throw new BizException("BAD_SCHEMA", "select.displayStyle is invalid");
        }
    }

    private void validateChecklistResultColors(com.fasterxml.jackson.databind.JsonNode node) {
        node.path("props").path("results").forEach(result -> {
            var color = result.get("color");
            if (color != null && !color.isNull()
                && (!color.isTextual() || !color.asText().matches("#[0-9a-fA-F]{6}"))) {
                throw new BizException("BAD_SCHEMA", "checklist result color is invalid");
            }
        });
    }

    public void validateSelectOptions(com.fasterxml.jackson.databind.JsonNode node) {
        // 空对象 {} 是设计器"已选外部数据源但尚未选版本"的中途状态，按未绑定回退到内置 options；
        // 只有带 sourceId 才走外部数据源校验（sourceId 在但 versionId 缺失仍会由 requireVersion 报错）。
        if (com.antflow.form.options.OptionRuntimeService.isBound(node.path("props").path("optionSource"))) {
            if (optionRuntimeService == null) {
                throw new BizException("BAD_SCHEMA", "共享选项服务不可用");
            }
            optionRuntimeService.validateBinding(node, node.path("props").path("optionSource"));
            return;
        }
        var options = node.path("props").path("options");
        if (!options.isArray() || options.isEmpty()) {
            throw new BizException("BAD_SCHEMA", node.path("label").asText(node.path("id").asText()) + " requires options");
        }
        options.forEach(option -> {
            if (!option.isObject() || !option.path("label").isTextual()
                || option.path("label").asText().isBlank()
                || (!option.path("value").isTextual() && !option.path("value").isNumber())) {
                throw new BizException("BAD_SCHEMA", node.path("label").asText(node.path("id").asText()) + " has an incomplete option");
            }
        });
    }

    public void validatePublishingNode(com.fasterxml.jackson.databind.JsonNode node) {
        String type = node.path("type").asText("");
        if (Set.of("select", "radio", "multi_select", "checkbox").contains(type)) {
            validateSelectOptions(node);
        }
        if (Set.of("select", "multi_select").contains(type)) {
            validateSelectDisplayStyle(node);
        }
        if ("checklist".equals(type)) {
            validateChecklistResultColors(node);
        }
        if ("audio_upload".equals(type)) {
            var maxCountValue = node.path("props").path("maxCount");
            var maxDurationValue = node.path("props").path("maxDuration");
            int maxCount = maxCountValue.asInt(3);
            int maxDuration = maxDurationValue.asInt(60);
            if ((!maxCountValue.isMissingNode() && !maxCountValue.isIntegralNumber())
                || (!maxDurationValue.isMissingNode() && !maxDurationValue.isIntegralNumber())
                || maxCount < 1 || maxCount > 10 || maxDuration < 5 || maxDuration > 60) {
                throw new BizException("BAD_SCHEMA", "audio_upload limits are invalid");
            }
        }
        validateDisplayCondition(node.path("props").path("displayCondition"));
        node.path("children").forEach(this::validatePublishingNode);
    }

    private void validateDisplayCondition(com.fasterxml.jackson.databind.JsonNode displayCondition) {
        if ("in".equals(displayCondition.path("operator").asText())) {
            var value = displayCondition.path("value");
            if (!value.isArray() || value.isEmpty()) {
                throw new BizException("BAD_SCHEMA", "displayCondition.in value must be a non-empty array");
            }
            value.forEach(item -> {
                if (!item.isTextual() && !item.isNumber()) {
                    throw new BizException("BAD_SCHEMA", "displayCondition.in values must be strings or numbers");
                }
            });
        }
    }
}
