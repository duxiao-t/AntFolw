package com.antflow.form.options;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 选项 schema 的**纯函数**工具：字段树查找、分步拆级、JSON 取值、统一报错。
 *
 * <p>从 {@link OptionRuntimeService} 搬出（2026-10-22 类体积治理第一刀）。这些方法没有状态、
 * 不碰库，却被"运行时查询""绑定校验""引用统计""schema 解析"四处同时使用：留在任何一个业务类里，
 * 都会逼着另外三个类反向 import 它。所以单独放这里，用静态导入让搬迁后的方法体一字不改。
 *
 * <p>两条容易踩的语义：
 * <ol>
 *   <li>{@link #depth} 与 {@link #parts} 是**分步维度**的唯一实现：列模式按 {@code levelColumns} 顺序，
 *       编码模式按 {@code split.lengths} 的码点切分。`parts` 返回空列表表示"这行不满足级数/编码长度"，
 *       由调用方过滤掉——不是错误，别在这里抛异常（源数据有短码是正常的）。</li>
 *   <li>{@link #bad} 恒为 {@code BAD_SCHEMA}、{@link #invalid} 恒为 {@code FORM_DATA_INVALID}：
 *       错误码与文案是对外契约，拆分时不许顺手统一。</li>
 * </ol>
 */
final class OptionSchemaSupport {
    private OptionSchemaSupport() { }

    static int depth(JsonNode cascade) {
        if (cascade.isMissingNode() || cascade.isNull()) return 0;
        if ("columns".equals(cascade.path("kind").asText())) {
            JsonNode levels = cascade.path("levelColumns");
            if (!levels.isArray() || levels.isEmpty() || levels.size() > 10) bad("分类步骤必须为 1 到 10 级");
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode c : levels) if (!c.isTextual() || c.asText().isBlank() || !seen.add(c.asText())) bad("分类列不能为空或重复");
            return levels.size();
        }
        JsonNode split = cascade.path("split"), lengths = split.path("lengths");
        if (!"split".equals(cascade.path("kind").asText()) || !"fixed".equals(split.path("kind").asText())
            || !lengths.isArray() || lengths.isEmpty() || lengths.size() > 10) bad("编码前缀步骤必须为 1 到 10 级");
        for (JsonNode length : lengths) if (!length.isIntegralNumber() || length.asInt() < 1 || length.asInt() > 50) bad("每步编码长度必须为 1 到 50");
        return lengths.size();
    }

    static List<String> parts(Map<String, String> row, JsonNode cascade) {
        int depth = depth(cascade);
        List<String> parts = new ArrayList<>();
        if (depth == 0) return parts;
        if ("columns".equals(cascade.path("kind").asText())) {
            for (JsonNode column : cascade.path("levelColumns")) {
                String part = row.get(column.asText()); if (part == null || part.isBlank()) return List.of();
                parts.add(part);
            }
        } else {
            String code = row.get(text(cascade, "sourceColumn"));
            if (code == null) return List.of();
            int offset = 0;
            for (JsonNode length : cascade.path("split").path("lengths")) {
                if (code.codePointCount(offset, code.length()) < length.asInt()) return List.of();
                int end = code.offsetByCodePoints(offset, length.asInt());
                parts.add(code.substring(offset, end)); offset = end;
            }
        }
        return parts;
    }

    static List<JsonNode> flatten(JsonNode nodes) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(node);
            if ("span_layout".equals(node.path("type").asText())) result.addAll(flatten(node.path("children")));
        }
        return result;
    }

    /** 明细行是独立的取值域：先在本层找字段，找不到才下钻 table_list 的子 schema。 */
    static List<JsonNode> scopeFor(JsonNode schema, String id) {
        List<JsonNode> scope = flatten(schema);
        if (field(scope, id) != null) return scope;
        for (JsonNode node : scope) if ("table_list".equals(node.path("type").asText())) {
            List<JsonNode> nested = scopeFor(node.path("children"), id); if (!nested.isEmpty()) return nested;
        }
        return List.of();
    }

    static JsonNode field(List<JsonNode> scope, String id) { return scope.stream().filter(n -> n.path("id").asText().equals(id)).findFirst().orElse(null); }

    static String parentId(JsonNode node) { return node.path("props").path("dataLinkage").isObject()
        ? text(node.path("props").path("dataLinkage"), "fieldId") : text(node.path("props").path("optionSource").path("dependency"), "fieldId"); }

    /**
     * 只有带正整数 sourceId 的 optionSource 才算"已绑定"。
     * 设计器在"选择数据源版本"之前会写入空对象 {@code {}}，那不是绑定，必须按未绑定处理：
     * 否则它会被当成外部数据源走进 requireVersion，报"数据源和版本不能为空"，导致表单无法发布。
     */
    static boolean isBound(JsonNode source) {
        return source.isObject() && source.path("sourceId").isIntegralNumber()
            && source.path("sourceId").asLong() > 0;
    }

    static boolean dynamic(JsonNode node) { return isBound(node.path("props").path("optionSource")) || node.path("props").path("dataLinkage").isObject(); }

    static String text(JsonNode node, String key) { return node.path(key).asText("").trim(); }

    static boolean empty(Object v) { return v == null || "".equals(v) || v instanceof List<?> list && list.isEmpty(); }

    static void bad(String message) { throw new BizException("BAD_SCHEMA", message); }

    static void invalid(String message) { throw new BizException("FORM_DATA_INVALID", message); }
}
