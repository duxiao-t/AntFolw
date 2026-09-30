package com.antflow.form.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 台账/导出的「显示文本」：把 {@code t_form_data.data} 里的原始值翻成人看得懂的内容。
 *
 * <p>以前这套规则只活在前端 `pages/admin/FormData/fieldValues.ts`，于是导出只能吐原始值
 * （`option_1`、`id=item-1; …`），而前端还得为了一个可选请求去取表单定义（取不到就 403 弹全局
 * 错误）。规则搬到后端后，页面与导出共用同一份实现，前端只渲染字符串。
 *
 * <p>三条与"字符串拼接"无关的硬要求：
 * <ul>
 *   <li>字典按**每条记录自己的版本**解析（由调用方按版本分组后传入 schema），否则表单改过标签后
 *       旧单据会显示新标签；</li>
 *   <li>外链下拉（{@code props.optionSource}）的选项名不在 schema 里，要用 {@link OptionRef}
 *       去选项行里批量取——调用方负责查库并 {@link #bindLabels}；</li>
 *   <li>空值按**原始 value** 判断，不能拿"未填写"这类摘要文案当哨兵（选项真叫「未填写」时会被丢掉）。
 * </ul>
 */
public final class FormValueDisplay {
    /** 一条记录里出现的、需要去选项行里查标签的值上限（防止一个字段塞进上万个值当扫描器用）。 */
    private static final int MAX_LOOKUP_VALUES = 500;

    private final ObjectMapper json;
    private final Map<String, FieldMeta> metas = new LinkedHashMap<>();
    private final Map<String, OptionRef> refs = new LinkedHashMap<>();
    private final Map<String, Set<String>> pending = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> labels = new LinkedHashMap<>();

    public FormValueDisplay(ObjectMapper json, JsonNode schema, Collection<JsonNode> dataNodes) {
        this.json = json;
        collectMetas(schema);
        dataNodes.forEach(this::collectValues);
    }

    /** schema 里一个字段的类型与字典（`children` 里的字段同样收进来，明细表要靠它）。 */
    public record FieldMeta(String id, String label, String type, List<Option> options,
                            List<Item> results, List<Item> items, List<String> childIds) { }

    public record Option(String value, String label) { }

    public record Item(String id, String label) { }

    /** 外链下拉：标签要去选项行的哪个版本、哪两列取。 */
    public record OptionRef(String fieldId, long versionId, String valueColumn, String labelColumn) { }

    /** 本批真的用到的外链下拉（没人选过的字段不查库）。 */
    public List<OptionRef> pendingRefs() {
        return pending.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> refs.get(entry.getKey())).toList();
    }

    /** 本批该字段要查的值（已去重）。 */
    public Set<String> pendingValues(String fieldId) {
        return pending.getOrDefault(fieldId, Set.of());
    }

    public void bindLabels(String fieldId, Map<String, String> resolved) {
        if (!resolved.isEmpty()) labels.put(fieldId, resolved);
    }

    /** 字段标题，取不到退回字段 id（前端列头与抽屉标题都用它）。 */
    public String fieldLabel(String fieldId) {
        FieldMeta meta = metas.get(fieldId);
        return meta == null || meta.label().isBlank() ? fieldId : meta.label();
    }

    /** 一条记录的字段值列表（顺序同 data 里的顺序）。 */
    public List<FormData.FieldValue> values(JsonNode data) {
        if (data == null || !data.isObject()) return List.of();
        List<FormData.FieldValue> fields = new ArrayList<>();
        data.fields().forEachRemaining(entry -> fields.add(new FormData.FieldValue(
            entry.getKey(), fieldLabel(entry.getKey()), json.convertValue(entry.getValue(), Object.class),
            cell(entry.getKey(), entry.getValue()), detail(entry.getKey(), entry.getValue()))));
        return fields;
    }

    /** 单元格里的一句话；空值返回空串，由前端渲染成占位符。 */
    public String cell(String fieldId, JsonNode value) {
        FieldMeta meta = metas.get(fieldId);
        if (meta == null) return cellText(value);
        if ("checklist".equals(meta.type()) && value.isArray()) return checklistSummary(value, meta);
        String select = selectText(meta, value);
        return select == null ? cellText(value) : select;
    }

    /** 详情抽屉里的一项；展开来看，所以不压缩成一句话。 */
    public String detail(String fieldId, JsonNode value) {
        FieldMeta meta = metas.get(fieldId);
        if (meta == null) return detailText(value);
        if ("checklist".equals(meta.type()) && value.isArray()) return checklistDetail(value, meta);
        if ("table_list".equals(meta.type()) && value.isArray()) return tableListDetail(value, meta);
        String select = selectText(meta, value);
        if (select == null) return detailText(value);
        return select.isEmpty() ? "—" : select;
    }

    // ---- 字典 ----

    private void collectMetas(JsonNode schema) {
        if (schema == null || !schema.isArray()) return;
        for (JsonNode node : schema) {
            String id = node.path("id").asText("");
            JsonNode props = node.path("props");
            if (!id.isBlank()) {
                List<String> childIds = new ArrayList<>();
                node.path("children").forEach(child -> {
                    if (!child.path("id").asText("").isBlank()) childIds.add(child.path("id").asText());
                });
                metas.put(id, new FieldMeta(id, node.path("label").asText(""),
                    node.path("type").asText(""), options(props.path("options")),
                    items(props.path("results")), items(props.path("items")), childIds));
                JsonNode source = props.path("optionSource");
                if (source.path("sourceId").asLong(0) > 0 && source.path("versionId").asLong(0) > 0) {
                    refs.put(id, new OptionRef(id, source.path("versionId").asLong(),
                        source.path("valueColumn").asText(""), source.path("labelColumn").asText("")));
                }
            }
            collectMetas(node.path("children"));
        }
    }

    private List<Option> options(JsonNode nodes) {
        List<Option> list = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) return list;
        nodes.forEach(node -> list.add(new Option(node.path("value").asText(""),
            node.path("label").asText(node.path("value").asText("")))));
        return list;
    }

    private List<Item> items(JsonNode nodes) {
        List<Item> list = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) return list;
        nodes.forEach(node -> list.add(new Item(node.path("id").asText(""),
            node.path("label").asText(node.path("id").asText("")))));
        return list;
    }

    /** 收集本批出现的外链下拉值：明细表里的子字段也要往下走一层。 */
    private void collectValues(JsonNode data) {
        if (data == null || !data.isObject()) return;
        data.fields().forEachRemaining(entry -> collectFieldValues(entry.getKey(), entry.getValue()));
    }

    private void collectFieldValues(String fieldId, JsonNode value) {
        FieldMeta meta = metas.get(fieldId);
        if (meta == null) return;
        if (refs.containsKey(fieldId)) {
            Set<String> values = pending.computeIfAbsent(fieldId, key -> new LinkedHashSet<>());
            if (values.size() < MAX_LOOKUP_VALUES) {
                if (value.isArray()) value.forEach(item -> values.add(text(item)));
                else values.add(text(value));
                values.remove("");
            }
        }
        if ("table_list".equals(meta.type()) && value.isArray()) {
            value.forEach(row -> meta.childIds().forEach(childId ->
                collectFieldValues(childId, row.path(childId))));
        }
    }

    // ---- 单元格 ----

    /** 按值的形状判断（拿不到类型时才走这里）。 */
    private String cellText(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isNumber() || value.isBoolean()) return value.asText();
        if (value.isArray()) {
            if (value.isEmpty()) return "";
            if (allTextual(value)) {
                List<String> texts = new ArrayList<>();
                value.forEach(item -> texts.add(item.asText()));
                return String.join("、", texts);
            }
            return allAttachments(value) ? value.size() + " 个附件" : value.size() + " 项";
        }
        if (value.isObject()) return value.isEmpty() ? "" : value.size() + " 项";
        return value.asText();
    }

    private String detailText(JsonNode value) {
        if (value == null || value.isNull()) return "—";
        if (value.isTextual()) return value.asText().isEmpty() ? "—" : value.asText();
        if (value.isNumber() || value.isBoolean()) return value.asText();
        if (value.isArray()) {
            if (value.isEmpty()) return "—";
            if (allTextual(value)) {
                List<String> texts = new ArrayList<>();
                value.forEach(item -> texts.add(item.asText()));
                return String.join("、", texts);
            }
            if (allAttachments(value)) {
                List<String> names = new ArrayList<>();
                value.forEach(item -> {
                    String name = text(item.path("name"));
                    if (name.isEmpty()) name = text(item.path("fileName"));
                    if (!name.isEmpty()) names.add(name);
                });
                // 一个名字都取不到时给数量，总比一行提示符的空行好。
                return names.isEmpty() ? value.size() + " 个附件" : String.join("\n", names);
            }
            List<String> rows = new ArrayList<>();
            value.forEach(item -> rows.add(write(item)));
            return String.join("\n", rows);
        }
        return write(value);
    }

    /**
     * 下拉的显示文本；没有字典（既没绑外链、也没配静态选项）时返回 null，交给形状判断。
     *
     * <p>值查不到标签时回落**原始值**——表单改过选项后旧单据仍要看得见"当时选的是什么"。
     */
    private String selectText(FieldMeta meta, JsonNode value) {
        Map<String, String> dictionary = labels.get(meta.id());
        if (dictionary == null || dictionary.isEmpty()) {
            dictionary = new LinkedHashMap<>();
            for (Option option : meta.options()) dictionary.put(option.value(), option.label());
        }
        if (dictionary.isEmpty()) return null;
        List<String> selected = new ArrayList<>();
        if (!"multi_select".equals(meta.type())) {
            // 单选却拿到数组 = 脏数据，交给形状判断（按多选拼出来会误导）。
            if (value.isArray()) return null;
            addLabel(selected, value, dictionary);
        } else {
            if (!value.isArray()) return null;
            for (JsonNode item : value) addLabel(selected, item, dictionary);
        }
        return String.join("、", selected);
    }

    private static void addLabel(List<String> target, JsonNode value, Map<String, String> dictionary) {
        String raw = text(value);
        if (raw.isEmpty()) return;
        target.add(dictionary.getOrDefault(raw, raw));
    }

    /** 「通过 2 · 不适用 1」：检查项在单元格里只汇总各状态数量。 */
    private String checklistSummary(JsonNode value, FieldMeta meta) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        value.forEach(row -> {
            String label = itemLabel(meta.results(), text(row.path("status")), "未填");
            counts.merge(label, 1, Integer::sum);
        });
        List<String> parts = new ArrayList<>();
        counts.forEach((label, count) -> parts.add(label + " " + count));
        return String.join(" · ", parts);
    }

    /** 「检查项1：通过（1 张图：a.png）；说明：…；备注：…」，每个检查项一行。 */
    private String checklistDetail(JsonNode value, FieldMeta meta) {
        List<String> lines = new ArrayList<>();
        value.forEach(row -> {
            String id = text(row.path("id"));
            String name = text(row.path("name"));
            String label = itemLabel(meta.items(), id, name.isEmpty() ? id : name);
            String status = itemLabel(meta.results(), text(row.path("status")), "未填");
            JsonNode images = row.path("images").isArray() ? row.path("images") : row.path("photos");
            int count = images.isArray() ? images.size() : 0;
            StringBuilder line = new StringBuilder(label).append("：").append(status);
            if (count > 0) {
                List<String> names = new ArrayList<>();
                images.forEach(image -> {
                    String imageName = text(image.path("name"));
                    if (imageName.isEmpty()) imageName = text(image.path("fileName"));
                    if (!imageName.isEmpty()) names.add(imageName);
                });
                line.append("（").append(count).append(" 张图")
                    .append(names.isEmpty() ? "" : "：" + String.join("、", names)).append("）");
            }
            // 说明与备注是同一个概念的两种键名（ChecklistField 也是 description 优先）、
            // 不是两条独立信息，所以只出一句。
            String description = text(row.path("description"));
            if (description.isEmpty()) description = text(row.path("remark"));
            if (!description.isEmpty()) line.append("；说明：").append(description);
            lines.add(line.toString());
        });
        return String.join("\n", lines);
    }

    /** 明细表：每行 `列标签=显示值`，列之间 `；`。 */
    private String tableListDetail(JsonNode value, FieldMeta meta) {
        List<String> lines = new ArrayList<>();
        value.forEach(row -> {
            List<String> parts = new ArrayList<>();
            meta.childIds().forEach(childId -> {
                String text = cell(childId, row.path(childId));
                if (!text.isEmpty()) parts.add(fieldLabel(childId) + "=" + text);
            });
            lines.add(parts.isEmpty() ? "—" : String.join("；", parts));
        });
        return String.join("\n", lines);
    }

    private String itemLabel(List<Item> dictionary, String id, String fallback) {
        return dictionary.stream().filter(item -> item.id().equals(id)).findFirst()
            .map(Item::label).orElse(fallback);
    }

    /** 上传件带 contentUrl（或 url）；检查项之类的对象数组也有 name，不能只看 name。 */
    private static boolean allAttachments(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isObject() || !(item.has("contentUrl") || item.has("url"))) return false;
        }
        return true;
    }

    private static boolean allTextual(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isTextual()) return false;
        }
        return true;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return node.isTextual() ? node.asText() : node.asText("");
    }

    /**
     * 紧凑 JSON：抽屉里靠 {@code pre-wrap} 折行。不用 Jackson 的 pretty printer——它输出
     * `"status" : "pass"`（键值分隔符前多个空格），比 JSON 本来的样子还难读。
     */
    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(json.convertValue(value, Object.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return value.toString();
        }
    }
}
