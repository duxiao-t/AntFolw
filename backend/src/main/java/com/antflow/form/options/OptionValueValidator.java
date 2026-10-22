package com.antflow.form.options;

import static com.antflow.form.options.OptionSchemaSupport.bad;
import static com.antflow.form.options.OptionSchemaSupport.depth;
import static com.antflow.form.options.OptionSchemaSupport.dynamic;
import static com.antflow.form.options.OptionSchemaSupport.empty;
import static com.antflow.form.options.OptionSchemaSupport.field;
import static com.antflow.form.options.OptionSchemaSupport.flatten;
import static com.antflow.form.options.OptionSchemaSupport.invalid;
import static com.antflow.form.options.OptionSchemaSupport.isBound;
import static com.antflow.form.options.OptionSchemaSupport.parts;
import static com.antflow.form.options.OptionSchemaSupport.text;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 选项 schema 与提交值的校验：联动链、分步级数、值列/显示列、已选值是否真的存在于钉死版本里。
 *
 * <p>从 {@link OptionRuntimeService} 搬出（2026-10-22 类体积治理第一刀）。与查询路径的区别是
 * **判定方向**：查询把不合格的数据过滤掉（短码丢行），校验必须报错——所以不能共用同一套循环。
 *
 * <p><b>三条不能动的语义：</b>
 * <ol>
 *   <li>校验一律走 {@code requireVersion(..., false)}（读路径口径）：校验发生在"用这份 schema 读数据"时，
 *       钉着已停用版本的历史单据仍要能通过，把 forBinding=true 挪进来会让旧单据全部 422。</li>
 *   <li>联动链必须防环（{@code visited} 按字段 id 记），且父字段必须是同一取值域内的**外部单选下拉**；
 *       明细行（table_list）是独立取值域，父字段只在同一行内找。</li>
 *   <li>{@code dataLinkage} 形态（文本/数字取父字段的某一列）与 {@code optionSource.dependency}
 *       形态（下拉按上游值过滤）的匹配列取法不同，见 {@link #dependencyFilters}。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class OptionValueValidator {
    private final OptionSourceBinding binding;

    void validateValue(JsonNode field, Object value, Map<?, ?> values) {
        if (empty(value)) return;
        JsonNode source = field.path("props").path("optionSource");
        // 未真正绑定（`{}`）时按静态选项处理，不该走到外部值校验——调用方也会用 isBound 过滤，
        // 这里是第二道，避免别的入口把 `{}` 送进来直接报 BAD_SCHEMA。
        if (!isBound(source)) return;
        binding.requireVersion(field, source, false);
        boolean multiple = "multi_select".equals(field.path("type").asText());
        if (multiple != (value instanceof List<?>)) invalid("下拉值类型不正确");
        List<?> selected = value instanceof List<?> list ? list : List.of(value);
        Map<String, String> filters = new LinkedHashMap<>();
        JsonNode dependency = source.path("dependency");
        if (dependency.isObject()) {
            Object parent = values.get(text(dependency, "fieldId"));
            if (!(parent instanceof String) || empty(parent)) invalid("请先选择上游字段");
            filters.put(text(dependency, "matchColumn"), String.valueOf(parent));
        }
        Set<String> allowed = new LinkedHashSet<>();
        binding.rows(source, filters).forEach(row -> allowed.add(row.get(text(source, "valueColumn"))));
        if (selected.size() > 100 || new LinkedHashSet<>(selected).size() != selected.size()
            || selected.stream().anyMatch(v -> !(v instanceof String) || !allowed.contains(v))) invalid("包含不存在或与上游不匹配的选项");
    }

    /** A detail row is a separate value scope; columns share their parent's scope. */
    void validateSchema(JsonNode schema) {
        if (!schema.isArray()) bad("表单结构无效");
        List<JsonNode> scope = flatten(schema);
        for (JsonNode node : scope) {
            if ("table_list".equals(node.path("type").asText())) validateSchema(node.path("children"));
            if (!dynamic(node)) continue;
            JsonNode source = binding.source(node, scope);
            binding.requireVersion(node, source, true);
            validateChain(node, scope, new LinkedHashSet<>());
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map<String, String> row : binding.rows(source, Map.of())) {
                String value = row.get(text(source, "valueColumn")), label = row.get(text(source, "labelColumn"));
                if (value == null || value.isBlank() || label == null || label.isBlank()) bad(node.path("label").asText() + "：值列和显示列不能有空值");
                String previous = labels.putIfAbsent(value, label);
                if (previous != null && !previous.equals(label)) bad("同一个选项值对应多个显示名称：" + value);
                if (parts(row, source.path("cascade")).size() != depth(source.path("cascade"))) bad("分步分类列有空值或编码长度不足：" + value);
            }
        }
    }

    private void validateChain(JsonNode node, List<JsonNode> scope, Set<String> visited) {
        if (!visited.add(node.path("id").asText())) bad("选项联动不能循环引用");
        String parentId = OptionSchemaSupport.parentId(node);
        if (parentId.isEmpty()) return;
        JsonNode parent = field(scope, parentId);
        if (parent == null || !"select".equals(parent.path("type").asText()) || !isBound(parent.path("props").path("optionSource"))) bad("联动来源必须是同一表单或明细行内的外部单选下拉");
        JsonNode source = binding.source(node, scope), parentSource = binding.source(parent, scope);
        if (!Objects.equals(source.get("versionId"), parentSource.get("versionId"))
            || !Objects.equals(source.get("sourceId"), parentSource.get("sourceId"))) bad("联动字段必须使用相同数据源版本");
        if (isBound(node.path("props").path("optionSource"))
            && !text(source.path("dependency"), "matchColumn").equals(text(parentSource, "valueColumn"))) bad("联动匹配列必须等于上游下拉的值列");
        validateChain(parent, scope, visited);
    }

    void validateSubmission(JsonNode schema, Map<?, ?> values) {
        List<JsonNode> scope = flatten(schema);
        for (JsonNode node : scope) {
            Object value = values.get(node.path("id").asText());
            if ("table_list".equals(node.path("type").asText()) && value instanceof List<?> rows) {
                for (Object row : rows) if (row instanceof Map<?, ?> map) validateSubmission(node.path("children"), map);
            }
            if (!isBound(node.path("props").path("optionSource")) || empty(value)) continue;
            JsonNode source = binding.source(node, scope);
            binding.requireVersion(node, source, false);
            boolean multiple = "multi_select".equals(node.path("type").asText());
            if (multiple != (value instanceof List<?>)) invalid("下拉值类型不正确");
            List<?> selected = value instanceof List<?> list ? list : List.of(value);
            if (selected.size() > 100 || new LinkedHashSet<>(selected).size() != selected.size()) invalid("下拉选择数量无效或重复");
            Map<String, String> filters = new LinkedHashMap<>();
            if (!dependencyFilters(node, scope, values, filters, new LinkedHashSet<>())) invalid("请先选择上游字段");
            Set<String> allowed = new LinkedHashSet<>();
            binding.rows(source, filters).forEach(row -> allowed.add(row.get(text(source, "valueColumn"))));
            for (Object item : selected) if (!(item instanceof String) || !allowed.contains(item)) invalid("包含不存在或与上游不匹配的选项");
        }
    }

    /**
     * 递归收集联动过滤条件。返回 false 表示"上游还没选"，调用方据此决定是返回空页还是报错
     * （查询路径返回空页，提交路径报"请先选择上游字段"）——两条路径共用同一份口径。
     */
    boolean dependencyFilters(JsonNode node, List<JsonNode> scope, Map<?, ?> values,
                              Map<String, String> filters, Set<String> visited) {
        if (!visited.add(node.path("id").asText())) bad("选项联动不能循环引用");
        String parentId = OptionSchemaSupport.parentId(node);
        if (parentId.isEmpty()) return true;
        JsonNode parent = field(scope, parentId);
        if (parent == null) bad("联动来源字段不存在");
        Object value = values.get(parentId);
        if (!(value instanceof String) || empty(value)) return false;
        JsonNode parentSource = binding.source(parent, scope);
        String column = node.path("props").path("dataLinkage").isObject() ? text(parentSource, "valueColumn")
            : text(binding.source(node, scope).path("dependency"), "matchColumn");
        String old = filters.put(column, String.valueOf(value));
        if (old != null && !old.equals(value)) return false;
        return dependencyFilters(parent, scope, values, filters, visited);
    }
}
