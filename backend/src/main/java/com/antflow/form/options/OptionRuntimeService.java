package com.antflow.form.options;

import static com.antflow.form.options.OptionSchemaSupport.bad;
import static com.antflow.form.options.OptionSchemaSupport.depth;
import static com.antflow.form.options.OptionSchemaSupport.field;
import static com.antflow.form.options.OptionSchemaSupport.parts;
import static com.antflow.form.options.OptionSchemaSupport.scopeFor;
import static com.antflow.form.options.OptionSchemaSupport.text;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 选项源运行时入口：查询、预览、发布期校验、台账标签回查。
 *
 * <p>2026-10-22 按"变化原因"拆开（579 行 / 42 方法 → 157 行 / 13 方法），本类只留**编排**：
 * 参数校验 + 分页 + 结果装配。判定与读库分别下沉到：
 * <ul>
 *   <li>{@link OptionSchemaSupport}：纯函数（字段树、分步拆级、JSON 取值、统一报错）</li>
 *   <li>{@link OptionSchemaResolver}：三种入口 → 一份 schema，含节点级可见性</li>
 *   <li>{@link OptionSourceBinding}：绑定解析、版本钉死校验、按钉死版本读行</li>
 *   <li>{@link OptionValueValidator}：schema/提交值校验（联动链、分步、值列显示列）</li>
 *   <li>{@link OptionReferenceGuard}：新增绑定的「可引用表单」差集校验</li>
 * </ul>
 *
 * <p><b>本类没有缓存，也没有事务</b>：这里每次请求都读当前库，版本由 schema 里的 {@code versionId}
 * 钉死（见 {@link OptionSourceBinding#requireVersion}）。不要因为"看起来慢"在这里加一层缓存——
 * 数据源重新发布/停用后，缓存失效的时机（多实例、多公司）没有任何现成的失效信号可用。
 *
 * <p>公开方法名与签名是契约（{@code FormSchemaValidator}/{@code FormSubmissionValidator}/
 * {@code FormDefinitionService}/{@code FormDataService} 在编译期直接调用，静态方法 {@link #isBound}
 * 还被 {@code form.schema} 包引用），拆分只做委托，不许在这里再写判定逻辑。
 */
@Service
@RequiredArgsConstructor
public class OptionRuntimeService {
    private final AuthorizationService authorization;
    private final OptionSchemaResolver schemaResolver;
    private final OptionSourceBinding binding;
    private final OptionValueValidator validator;
    private final OptionReferenceGuard references;

    public OptionPage query(OptionQuery request) { return querySchema(schemaResolver.schema(request), request); }

    /** 见 {@link OptionSourceBinding#labelsForValues}：按 schema 钉死的 versionId 回查标签，不校验发布态。 */
    public Map<String, String> labelsForValues(long versionId, String valueColumn,
                                               String labelColumn, Collection<String> values) {
        return binding.labelsForValues(versionId, valueColumn, labelColumn, values);
    }

    public OptionPage preview(long formId, PreviewQuery request) {
        authorization.requireFormMaintenance(formId, PermissionCodes.FORM_DEFINITION_MANAGE);
        if (request == null || request.schema() == null || !request.schema().isArray()) bad("预览表单无效");
        validator.validateSchema(request.schema());
        return querySchema(request.schema(), request.query());
    }

    private OptionPage querySchema(JsonNode schema, OptionQuery request) {
        if (request == null || request.fieldId() == null || request.fieldId().isBlank()) bad("fieldId 不能为空");
        if (request.page() < 0 || request.page() > 1_000_000 || request.size() < 0 || request.size() > 100) bad("分页参数无效");
        if (request.keyword() != null && request.keyword().length() > 500) bad("搜索内容过长");
        List<JsonNode> scope = scopeFor(schema, request.fieldId());
        JsonNode field = field(scope, request.fieldId());
        if (field == null) throw new HiddenResourceException("option field not found");
        JsonNode source = binding.source(field, scope);
        binding.requireVersion(field, source, false);
        List<String> path = request.path() == null ? List.of() : request.path();
        if (path.size() > 10 || path.stream().anyMatch(v -> v == null || v.length() > 500)) bad("分步路径无效");
        List<String> selected = request.selectedValues();
        if (selected != null && (selected.size() > 100 || selected.stream().anyMatch(v -> v == null || v.length() > 500))) bad("回查值无效");
        Map<String, Object> values = request.values() == null ? Map.of() : request.values();
        Map<String, String> filters = new LinkedHashMap<>();
        // Label lookup ignores editing paths, but only uses the field's immutable version.
        if (selected == null && !validator.dependencyFilters(field, scope, values, filters, new LinkedHashSet<>())) {
            return new OptionPage("OPTIONS", 0, 0, List.of(), 0, 1, size(request));
        }
        List<Map<String, String>> rows = binding.rows(source, filters);
        String valueColumn = text(source, "valueColumn"), labelColumn = text(source, "labelColumn");
        JsonNode cascade = source.path("cascade");
        int depth = depth(cascade);
        if (path.size() > depth) bad("分步路径无效");
        boolean level = selected == null && path.size() < depth;
        List<OptionValue> items = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (selected != null) {
                if (selected.contains(row.get(valueColumn))) items.add(new OptionValue(row.get(valueColumn), row.get(labelColumn)));
                continue;
            }
            List<String> parts = parts(row, cascade);
            if (parts.size() != depth || !parts.subList(0, path.size()).equals(path)) continue;
            items.add(level ? new OptionValue(parts.get(path.size()), parts.get(path.size()))
                : new OptionValue(row.get(valueColumn), row.get(labelColumn)));
        }
        String keyword = request.keyword() == null ? "" : request.keyword().trim().toLowerCase(Locale.ROOT);
        items = items.stream().filter(item -> item.value() != null && !item.value().isBlank())
            .distinct().filter(item -> keyword.isEmpty() || item.value().toLowerCase(Locale.ROOT).contains(keyword)
                || item.label().toLowerCase(Locale.ROOT).contains(keyword))
            .sorted(Comparator.comparing(OptionValue::label).thenComparing(OptionValue::value)).toList();
        int page = Math.max(1, request.page()), size = selected == null ? size(request) : 100;
        int from = (int) Math.min(items.size(), (long) (page - 1) * size);
        return new OptionPage(level ? "LEVEL" : "OPTIONS", path.size(), depth,
            items.subList(from, Math.min(items.size(), from + size)), items.size(), page, size);
    }

    public void validateBinding(JsonNode field, JsonNode source) { binding.validateBinding(field, source); }

    public void validateValue(JsonNode field, Object value, Map<?, ?> values) {
        validator.validateValue(field, value, values);
    }

    /** 见 {@link OptionReferenceGuard}：只卡"本次新增的绑定对"，撤销引用不影响已有绑定。 */
    public void requireNewBindingsAreReferenced(long formId, String previousSchemaJson,
                                                String newSchemaJson) {
        references.requireNewBindingsAreReferenced(formId, previousSchemaJson, newSchemaJson);
    }

    public void validateSchema(JsonNode schema) { validator.validateSchema(schema); }

    public void validateSubmission(JsonNode schema, Map<?, ?> values) {
        validator.validateSubmission(schema, values);
    }

    /**
     * 只有带正整数 sourceId 的 optionSource 才算"已绑定"。
     * 实现搬到 {@link OptionSchemaSupport}，这里保留公开静态入口（{@code form.schema} 包与测试直接引用）。
     */
    public static boolean isBound(JsonNode source) {
        return OptionSchemaSupport.isBound(source);
    }

    /** 分步拆级（实现见 {@link OptionSchemaSupport#depth}），保留包内静态入口供既有测试直接调用。 */
    static int depth(JsonNode cascade) { return OptionSchemaSupport.depth(cascade); }

    /** 分步取值（实现见 {@link OptionSchemaSupport#parts}），保留包内静态入口供既有测试直接调用。 */
    static List<String> parts(Map<String, String> row, JsonNode cascade) {
        return OptionSchemaSupport.parts(row, cascade);
    }

    private static int size(OptionQuery r) { return r.size() == 0 ? 20 : r.size(); }
    public record OptionQuery(String formCode, Integer formVersion, Long instanceId, Long dataId, String fieldId,
        String keyword, int page, int size, Map<String, Object> values, List<String> path, List<String> selectedValues) { }
    public record PreviewQuery(JsonNode schema, OptionQuery query) { }
    public record OptionValue(String value, String label) { }
    public record OptionPage(String stage, int level, int totalLevels, List<OptionValue> items, long total, int page, int size) { }
}
