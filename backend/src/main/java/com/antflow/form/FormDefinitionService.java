package com.antflow.form;

import com.antflow.authz.FormGrantService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionMapper mapper;
    private final ObjectMapper json;
    private final FormGrantService formGrantService;

    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "DEPRECATED");
    private static final Set<String> FIELD_TYPES = Set.of(
        "text", "textarea", "number", "money",
        "radio", "checkbox", "select", "multi_select", "search",
        "date", "date_range", "time", "switch",
        "image_upload", "video_upload", "file_upload", "checklist",
        "user_picker", "dept_picker",
        "description", "span_layout", "table_list", "matrix_fill"
    );
    private static final Set<String> CONTAINER_TYPES = Set.of("span_layout");

    public Page<FormDefinitionMapper.Summary> list(long page, long size, String keyword,
                                                   String status) {
        return list(page, size, keyword, status, null, true);
    }

    public Page<FormDefinitionMapper.Summary> list(long page, long size, String keyword,
                                                   String status, Long userId, boolean admin) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        if (status != null && !status.isBlank()) {
            validateStatus(status);
        }
        return mapper.selectSummaryPage(Page.of(safePage, safeSize), normalized(keyword),
            normalized(status), userId, admin);
    }

    public FormDefinition getByCode(String code) {
        return mapper.selectOne(new QueryWrapper<FormDefinition>().eq("code", code));
    }

    public FormDefinition getPublishedByCode(String code) {
        return mapper.selectOne(new QueryWrapper<FormDefinition>()
            .eq("code", code)
            .eq("status", "PUBLISHED"));
    }
    public FormDefinition getById(Long id) { return mapper.selectById(id); }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Transactional
    public FormDefinition saveDraft(Long id, String code, String name,
                                    String description, Object schema,
                                    Object settings, Long userId) {
        FormDefinition fd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<FormDefinition>().eq("code", code)) > 0) {
                throw new BizException("CODE_EXISTS", "form code already exists: " + code);
            }
            fd = new FormDefinition();
            fd.setCode(code);
            fd.setName(name);
            fd.setDescription(description);
            fd.setVersion(1);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings == null ? java.util.Map.of() : settings));
            fd.setStatus("DRAFT");
            fd.setCreatedBy(userId);
            fd.setDeleted(0);
            mapper.insert(fd);
            formGrantService.grantCreator(fd.getId(), userId);
        } else {
            fd = mapper.selectById(id);
            if (fd == null) {
                throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
            }
            if ("DEPRECATED".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can be edited");
            }
            fd.setName(name);
            fd.setDescription(description);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings));
            fd.setStatus("DRAFT");
            mapper.updateById(fd);
        }
        return fd;
    }

    @Transactional
    public FormDefinition update(Long id, String name, String description,
                                 String status, Object schema, Object settings) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        if (name != null) fd.setName(name);
        if (description != null) fd.setDescription(description);
        if (status != null) {
            validateStatus(status);
            if ("PUBLISHED".equals(status) && !"PUBLISHED".equals(fd.getStatus())) {
                throw new BizException("USE_PUBLISH", "Use publish endpoint to publish a form");
            }
            fd.setStatus(status);
        }
        if (schema != null || settings != null) {
            if (!"DRAFT".equals(fd.getStatus())) {
                throw new BizException("NOT_DRAFT", "Only DRAFT form_definitions can change schema/settings");
            }
            if (schema != null) fd.setSchema(writeJson(schema));
            if (settings != null) fd.setSettings(writeJson(settings));
        }
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public FormDefinition publish(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        if (!"DRAFT".equals(fd.getStatus())) return fd;
        validateSchema(fd.getSchema());
        fd.setStatus("PUBLISHED");
        fd.setVersion(fd.getVersion() + 1);
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public FormDefinition disable(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        fd.setStatus("DEPRECATED");
        mapper.updateById(fd);
        return fd;
    }

    @Transactional
    public void softDelete(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (fd == null) {
            throw new BizException("FORM_NOT_FOUND", "Form not found: " + id);
        }
        mapper.deleteById(id);
    }

    public void validateSubmission(String schema, Object data) {
        var root = parseSchema(schema);
        Map<?, ?> values = data instanceof Map<?, ?> map ? map : Map.of();
        for (var node : root) {
            validateNodeValue(node, values);
        }
    }

    /** 只校验指定字段集合（节点级字段权限用）：容器递归，其余字段跳过。 */
    public void validateSubmission(String schema, Object data, Set<String> fieldIds) {
        var root = parseSchema(schema);
        Map<?, ?> values = data instanceof Map<?, ?> map ? map : Map.of();
        for (var node : root) {
            validateNodeValue(node, values, fieldIds);
        }
    }

    /** 返回可配置字段权限的叶子字段 id（排除分栏/明细表/说明等展示型节点，含嵌套子字段）。 */
    public Set<String> leafFieldIds(String schema) {
        return leafFieldTypes(schema).keySet();
    }

    /** 返回叶子字段 id → 字段类型映射，供发布校验判断可编辑类型。 */
    public Map<String, String> leafFieldTypes(String schema) {
        var root = parseSchema(schema);
        var types = new java.util.LinkedHashMap<String, String>();
        for (var node : root) {
            collectLeafFieldTypes(node, types);
        }
        return java.util.Collections.unmodifiableMap(types);
    }

    private void collectLeafFieldTypes(com.fasterxml.jackson.databind.JsonNode node,
                                       Map<String, String> types) {
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type) || "table_list".equals(type)) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> collectLeafFieldTypes(child, types));
            }
            return;
        }
        if ("description".equals(type)) {
            return;
        }
        types.put(node.path("id").asText(), type);
    }

    private void validateNodeValue(com.fasterxml.jackson.databind.JsonNode node,
                                   Map<?, ?> values, Set<String> fieldIds) {
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type) || "table_list".equals(type)) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> validateNodeValue(child, values, fieldIds));
            }
            return;
        }
        if (!fieldIds.contains(node.path("id").asText())) {
            return;
        }
        validateNodeValue(node, values);
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private void validateSchema(String s) {
        parseSchema(s).forEach(this::validatePublishingNode);
    }

    private List<com.fasterxml.jackson.databind.JsonNode> parseSchema(String s) {
        if (s == null) {
            throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
        }
        try {
            var arr = json.readTree(s);
            if (!arr.isArray() || arr.size() == 0) {
                throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
            }
            var nodes = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            arr.forEach(n -> {
                validateNode(n);
                nodes.add(n);
            });
            return nodes;
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_SCHEMA_JSON", e.getMessage());
        }
    }

    private void validateNode(com.fasterxml.jackson.databind.JsonNode node) {
        String id = node.path("id").asText("");
        String type = node.path("type").asText("");
        if (id.isBlank()) {
            throw new BizException("BAD_SCHEMA", "node.id is required");
        }
        if (type.isBlank() || !FIELD_TYPES.contains(type)) {
            throw new BizException("BAD_SCHEMA", "unsupported field type: " + type);
        }
        var rules = node.path("rules");
        if (!rules.isMissingNode() && !rules.isObject()) {
            throw new BizException("BAD_SCHEMA", "node.rules must be an object");
        }
        if ("matrix_fill".equals(type)) {
            validateMatrixSchema(node);
        }
        var children = node.path("children");
        if (!children.isMissingNode()) {
            if (!children.isArray()) {
                throw new BizException("BAD_SCHEMA", "node.children must be an array");
            }
            children.forEach(this::validateNode);
        }
    }

    private void validateNodeValue(com.fasterxml.jackson.databind.JsonNode node, Map<?, ?> values) {
        if (CONTAINER_TYPES.contains(node.path("type").asText(""))) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> validateNodeValue(child, values));
            }
            return;
        }
        Object value = values.get(node.path("id").asText());
        var props = node.path("props");
        var rules = node.path("rules");
        if ("matrix_fill".equals(node.path("type").asText())) {
            validateMatrixSubmission(node, value);
            return;
        }
        boolean required = rules.path("required").asBoolean(props.path("required").asBoolean(false));
        if (required && isEmpty(value)) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " is required");
        }
        int maxLength = rules.path("maxLength").asInt(props.path("maxLength").asInt(-1));
        if (maxLength > -1 && value instanceof String s && s.length() > maxLength) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxLength");
        }
        int minChecked = rules.path("minChecked").asInt(-1);
        int maxChecked = rules.path("maxChecked").asInt(props.path("maxSelected").asInt(-1));
        if (value instanceof List<?> list) {
            if (minChecked > -1 && list.size() < minChecked) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " below minChecked");
            }
            if (maxChecked > -1 && list.size() > maxChecked) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxChecked");
            }
        }
        var children = node.path("children");
        if (children.isArray()) {
            children.forEach(child -> validateNodeValue(child, values));
        }
    }

    private void validateMatrixSchema(com.fasterxml.jackson.databind.JsonNode node) {
        var props = node.path("props");
        if (!props.isObject()) {
            throw new BizException("BAD_SCHEMA", "matrix_fill.props must be an object");
        }
        var rows = readMatrixAxis(props.path("rows"), "rows", true, null);
        var columns = readMatrixAxis(props.path("columns"), "columns", true, null);
        int maxRows = positiveMatrixLimit(props, "maxRows");
        int maxColumns = positiveMatrixLimit(props, "maxColumns");
        if (rows.size() > maxRows) {
            throw new BizException("BAD_SCHEMA", "matrix_fill.rows exceeds maxRows");
        }
        if (columns.size() > maxColumns) {
            throw new BizException("BAD_SCHEMA", "matrix_fill.columns exceeds maxColumns");
        }

        String cellType = props.path("cellType").asText("");
        if (!Set.of("textarea", "number").contains(cellType)) {
            throw new BizException("BAD_SCHEMA", "matrix_fill.cellType must be textarea or number");
        }
        if ("textarea".equals(cellType)) {
            if (!props.path("maxLength").canConvertToInt() || props.path("maxLength").asInt() < 1) {
                throw new BizException("BAD_SCHEMA", "matrix_fill.maxLength must be a positive integer");
            }
        } else {
            var precision = props.path("precision");
            if (!precision.canConvertToInt() || precision.asInt() < 0 || !precision.isIntegralNumber()) {
                throw new BizException("BAD_SCHEMA", "matrix_fill.precision must be a non-negative integer");
            }
            var min = props.path("min");
            var max = props.path("max");
            if (!min.isMissingNode() && !min.isNumber()) {
                throw new BizException("BAD_SCHEMA", "matrix_fill.min must be a number");
            }
            if (!max.isMissingNode() && !max.isNumber()) {
                throw new BizException("BAD_SCHEMA", "matrix_fill.max must be a number");
            }
            if (min.isNumber() && max.isNumber() && min.decimalValue().compareTo(max.decimalValue()) > 0) {
                throw new BizException("BAD_SCHEMA", "matrix_fill.min must not exceed max");
            }
        }
    }

    private void validateMatrixSubmission(com.fasterxml.jackson.databind.JsonNode node, Object rawValue) {
        var props = node.path("props");
        var rules = node.path("rules");
        boolean required = rules.path("required").asBoolean(props.path("required").asBoolean(false));
        if (rawValue != null && !(rawValue instanceof Map<?, ?>)) {
            throw new BizException("FORM_DATA_INVALID", matrixLabel(node) + " must be an object");
        }
        var value = rawValue == null
            ? json.createObjectNode()
            : json.valueToTree(rawValue);
        var configuredRows = readMatrixAxis(props.path("rows"), "rows", true, null);
        var configuredColumns = readMatrixAxis(props.path("columns"), "columns", true, null);
        var customRows = readMatrixAxis(value.path("customRows"), "customRows", false, "runtime_row_");
        var customColumns = readMatrixAxis(value.path("customColumns"), "customColumns", false, "runtime_column_");
        ensureNoMatrixAxisCollision(configuredRows, customRows, "row");
        ensureNoMatrixAxisCollision(configuredColumns, customColumns, "column");

        var rows = new java.util.ArrayList<MatrixAxisItem>(configuredRows);
        rows.addAll(customRows);
        var columns = new java.util.ArrayList<MatrixAxisItem>(configuredColumns);
        columns.addAll(customColumns);
        if (rows.size() > props.path("maxRows").asInt()) {
            throw new BizException("FORM_DATA_INVALID", matrixLabel(node) + " exceeds maxRows");
        }
        if (columns.size() > props.path("maxColumns").asInt()) {
            throw new BizException("FORM_DATA_INVALID", matrixLabel(node) + " exceeds maxColumns");
        }

        var cells = value.path("cells");
        if (!cells.isMissingNode() && !cells.isObject()) {
            throw new BizException("FORM_DATA_INVALID", matrixLabel(node) + ".cells must be an object");
        }
        String cellType = props.path("cellType").asText();
        for (var row : rows) {
            var rowCells = cells.path(row.id());
            if (!rowCells.isMissingNode() && !rowCells.isObject()) {
                throw new BizException("FORM_DATA_INVALID", row.label() + " cells must be an object");
            }
            for (var column : columns) {
                var cell = rowCells.path(column.id());
                validateMatrixCell(props, required, cellType, row, column, cell);
            }
        }
    }

    private void validateMatrixCell(com.fasterxml.jackson.databind.JsonNode props,
                                    boolean required,
                                    String cellType,
                                    MatrixAxisItem row,
                                    MatrixAxisItem column,
                                    com.fasterxml.jackson.databind.JsonNode cell) {
        String coordinate = row.label() + " / " + column.label();
        boolean empty = cell.isMissingNode() || cell.isNull()
            || (cell.isTextual() && cell.asText().isBlank());
        if (empty) {
            if (required) {
                throw new BizException("FORM_DATA_INVALID", coordinate + " is required");
            }
            return;
        }
        if ("textarea".equals(cellType)) {
            if (!cell.isTextual()) {
                throw new BizException("FORM_DATA_INVALID", coordinate + " must be text");
            }
            if (cell.asText().length() > props.path("maxLength").asInt()) {
                throw new BizException("FORM_DATA_INVALID", coordinate + " exceeds maxLength");
            }
            return;
        }
        if (!cell.isNumber()) {
            throw new BizException("FORM_DATA_INVALID", coordinate + " must be a number");
        }
        var number = cell.decimalValue();
        var min = props.path("min");
        var max = props.path("max");
        if (min.isNumber() && number.compareTo(min.decimalValue()) < 0) {
            throw new BizException("FORM_DATA_INVALID", coordinate + " is below min");
        }
        if (max.isNumber() && number.compareTo(max.decimalValue()) > 0) {
            throw new BizException("FORM_DATA_INVALID", coordinate + " exceeds max");
        }
        int scale = Math.max(0, number.stripTrailingZeros().scale());
        if (scale > props.path("precision").asInt()) {
            throw new BizException("FORM_DATA_INVALID", coordinate + " exceeds precision");
        }
    }

    private java.util.List<MatrixAxisItem> readMatrixAxis(com.fasterxml.jackson.databind.JsonNode axis,
                                                          String name,
                                                          boolean required,
                                                          String idPrefix) {
        if (axis.isMissingNode() || axis.isNull()) {
            if (required) throw new BizException("BAD_SCHEMA", "matrix_fill." + name + " is required");
            return java.util.List.of();
        }
        if (!axis.isArray() || (required && axis.isEmpty())) {
            throw new BizException(required ? "BAD_SCHEMA" : "FORM_DATA_INVALID",
                "matrix_fill." + name + " must be " + (required ? "a non-empty array" : "an array"));
        }
        var result = new java.util.ArrayList<MatrixAxisItem>();
        var ids = new java.util.HashSet<String>();
        var labels = new java.util.HashSet<String>();
        for (var item : axis) {
            String id = item.path("id").asText("").trim();
            String label = item.path("label").asText("").trim();
            String errorCode = required ? "BAD_SCHEMA" : "FORM_DATA_INVALID";
            if (!item.isObject() || id.isBlank() || label.isBlank()) {
                throw new BizException(errorCode, "matrix_fill." + name + " items require id and label");
            }
            if (idPrefix != null && !id.startsWith(idPrefix)) {
                throw new BizException(errorCode, "matrix_fill." + name + " id must start with " + idPrefix);
            }
            if (!ids.add(id) || !labels.add(label)) {
                throw new BizException(errorCode, "matrix_fill." + name + " ids and labels must be unique");
            }
            result.add(new MatrixAxisItem(id, label));
        }
        return result;
    }

    private int positiveMatrixLimit(com.fasterxml.jackson.databind.JsonNode props, String key) {
        var value = props.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw new BizException("BAD_SCHEMA", "matrix_fill." + key + " must be a positive integer");
        }
        return value.asInt();
    }

    private void ensureNoMatrixAxisCollision(java.util.List<MatrixAxisItem> configured,
                                             java.util.List<MatrixAxisItem> custom,
                                             String axis) {
        var ids = configured.stream().map(MatrixAxisItem::id).collect(java.util.stream.Collectors.toSet());
        var labels = configured.stream().map(MatrixAxisItem::label).collect(java.util.stream.Collectors.toSet());
        for (var item : custom) {
            if (!ids.add(item.id()) || !labels.add(item.label())) {
                throw new BizException("FORM_DATA_INVALID", "matrix_fill custom " + axis + " conflicts with schema");
            }
        }
    }

    private String matrixLabel(com.fasterxml.jackson.databind.JsonNode node) {
        return node.path("label").asText(node.path("id").asText("matrix_fill"));
    }

    private record MatrixAxisItem(String id, String label) {}

    private void validateSelectOptions(com.fasterxml.jackson.databind.JsonNode node) {
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

    private void validatePublishingNode(com.fasterxml.jackson.databind.JsonNode node) {
        String type = node.path("type").asText("");
        if (Set.of("select", "multi_select").contains(type)) {
            validateSelectOptions(node);
        }
        node.path("children").forEach(this::validatePublishingNode);
    }

    private boolean isEmpty(Object value) {
        return value == null
            || (value instanceof String s && s.isBlank())
            || (value instanceof List<?> list && list.isEmpty())
            || (value instanceof Map<?, ?> map && map.isEmpty());
    }

    private void validateStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException("BAD_STATUS", "Unsupported form status: " + status);
        }
    }
}
