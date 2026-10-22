package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ROOT 字段权限落地：把发起人提交的值按"可编辑 / 只读 / 隐藏"合并成最终数据。
 *
 * <p>为什么独立：这是**写路径上唯一一处"客户端说了不算"的地方**——只读字段取 schema 默认值或
 * 历史值，隐藏字段直接丢弃，可编辑字段才收客户端的值。它同时被首填、草稿、返工三条路径复用，
 * 三条路径的差别只有两个开关（保留受限值 / 校验哪些字段），所以放在一个类里按开关分支，
 * 而不是复制三份。
 *
 * <p>三个不能改的行为：
 * <ol>
 *   <li>**返工保留只读字段的历史值**（{@code preserveRestricted}）：审批人改过的核定价不能因为
 *       发起人点了"重新提交"就被清掉或改回默认值。</li>
 *   <li>**首填不校验草稿结构**：{@code canonicalizeStarterData} 允许不完整数据落草稿；
 *       只有 {@code canonicalizeStarterSubmission} 才跑校验。</li>
 *   <li>**明细表逐行合并**：按行下标对齐历史值（本系统明细行没有稳定主键），
 *       行数变化时多出来的行按新行处理。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormStarterCanonicalizer {

    private static final Set<String> CONTAINER_TYPES =
        FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER);

    private final FormSchemaJson schemaJson;
    private final FormSchemaParser schemaParser;
    private final FormStarterProjector projector;
    private final FormVisibility visibility;
    private final FormSubmissionValidator submissionValidator;
    private final ObjectMapper json;

    /**
     * Applies the ROOT node field policy before a draft or workflow submission is persisted.
     * Missing ROOT permissions are intentionally treated as editable for old definitions.
     */
    public Map<String, Object> canonicalizeStarterSubmission(String schema, Object data,
                                                              Object process) {
        Map<String, Object> projected = canonicalizeStarterData(schema, data, process);
        validateStarterSubmission(schema, projected, process);
        return projector.filterVisibleSubmission(schema, projected);
    }

    /** Applies ROOT permissions without requiring an incomplete draft to pass validation. */
    public Map<String, Object> canonicalizeStarterData(String schema, Object data,
                                                        Object process) {
        return canonicalizeStarterData(schema, data, Map.of(), process, false);
    }

    /**
     * Applies ROOT permissions to a rework edit. Restricted values are retained from the
     * persisted revision, while editable values come from the initiator's request.
     */
    public Map<String, Object> canonicalizeStarterRevision(String schema, Object data,
                                                            Object existingData,
                                                            Object process) {
        return canonicalizeStarterData(schema, data,
            schemaJson.valueMap(schemaJson.readTree(existingData)), process, true);
    }

    /** Validates already-canonical initiator data while skipping ROOT-hidden fields. */
    public void validateStarterSubmission(String schema, Object data, Object process) {
        Map<String, String> modes = projector.starterFieldModes(process);
        Set<String> acceptedIds = new java.util.LinkedHashSet<>(schemaParser.leafFieldIds(schema));
        acceptedIds.removeIf(id -> "HIDDEN".equals(modes.get(id)));
        submissionValidator.validateSubmission(schema, data, acceptedIds);
    }

    /** Rework retains persisted restricted values, so only editable fields are revalidated. */
    public void validateStarterRevision(String schema, Object data, Object process) {
        Map<String, String> modes = projector.starterFieldModes(process);
        Set<String> editableIds = new java.util.LinkedHashSet<>(schemaParser.leafFieldIds(schema));
        editableIds.removeIf(id -> !"EDITABLE".equals(modes.getOrDefault(id, "EDITABLE")));
        submissionValidator.validateSubmission(schema, data, editableIds);
    }

    private Map<String, Object> canonicalizeStarterData(String schema, Object data,
                                                         Map<?, ?> existing,
                                                         Object process,
                                                         boolean preserveRestricted) {
        Map<?, ?> incoming = schemaJson.valueMap(data);
        Map<String, String> modes = projector.starterFieldModes(process);
        var projected = new java.util.LinkedHashMap<String, Object>();
        for (var node : schemaParser.parseSchema(schema)) {
            collectStarterValues(node, incoming, existing, projected, modes,
                preserveRestricted);
        }
        return projector.filterVisibleSubmission(schema, projected);
    }

    private void collectStarterValues(JsonNode node, Map<?, ?> incoming,
                                      Map<?, ?> existing, Map<String, Object> output,
                                      Map<String, String> modes,
                                      boolean preserveRestricted) {
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type)) {
            node.path("children").forEach(child ->
                collectStarterValues(child, incoming, existing, output, modes,
                    preserveRestricted));
            return;
        }
        if ("table_list".equals(type)) {
            collectStarterTable(node, incoming, existing, output, modes,
                preserveRestricted);
            return;
        }
        String id = node.path("id").asText();
        String mode = modes.getOrDefault(id, "EDITABLE");
        if ("description".equals(type)) return;
        if (preserveRestricted && !"EDITABLE".equals(mode)) {
            if (existing.containsKey(id)) output.put(id, existing.get(id));
            return;
        }
        if ("HIDDEN".equals(mode)) return;
        if ("READONLY".equals(mode)) {
            JsonNode defaultValue = node.path("props").path("defaultValue");
            if (!defaultValue.isMissingNode() && !defaultValue.isNull()) {
                output.put(id, json.convertValue(defaultValue, Object.class));
            }
            return;
        }
        if (incoming.containsKey(id)) output.put(id, incoming.get(id));
    }

    private void collectStarterTable(JsonNode node, Map<?, ?> incoming,
                                     Map<?, ?> existing, Map<String, Object> output,
                                     Map<String, String> modes,
                                     boolean preserveRestricted) {
        JsonNode children = node.path("children");
        if (!children.isArray() || !projector.hasStarterVisibleField(children, modes)) return;
        String id = node.path("id").asText();
        Object rawRows = incoming.get(id);
        if (rawRows == null) return;
        if (!(rawRows instanceof List<?> rows)) {
            throw new BizException("FORM_DATA_INVALID",
                node.path("label").asText(id) + " must be a row list");
        }
        List<?> existingRows = existing.get(id) instanceof List<?> values
            ? values : List.of();
        var projectedRows = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 0; index < rows.size(); index++) {
            Object rawRow = rows.get(index);
            if (!(rawRow instanceof Map<?, ?> row)) {
                throw new BizException("FORM_DATA_INVALID",
                    node.path("label").asText(id) + " contains an invalid row");
            }
            Map<?, ?> existingRow = index < existingRows.size()
                && existingRows.get(index) instanceof Map<?, ?> value ? value : Map.of();
            var projectedRow = new java.util.LinkedHashMap<String, Object>();
            children.forEach(child -> collectStarterValues(child, row, existingRow,
                projectedRow, modes, preserveRestricted));
            Set<String> visibleIds = visibility.visibleNodeIds(children, projectedRow);
            var visibleRow = new java.util.LinkedHashMap<String, Object>();
            children.forEach(child -> projector.collectVisibleValues(child, projectedRow, visibleRow,
                visibleIds));
            projectedRows.add(visibleRow);
        }
        output.put(id, projectedRows);
    }
}
