package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code matrix_fill} 控件的行列轴语义：发布时的结构约束与提交时的单元格校验。
 *
 * <p>为什么独立：矩阵是唯一"行列都是数据"的控件——行列既有配置态（props.rows/columns）
 * 又有运行态（cells/customRows/customColumns），提交校验必须先做一次轴合并再逐格校验。
 * 这段逻辑原来夹在 1400 行的通用校验里，改矩阵规则要和明细表、选项、部门选择器一起读。
 *
 * <p>三条容易改错的规则：
 * <ol>
 *   <li>**运行态 id 必须带前缀**（{@code runtime_row_} / {@code runtime_column_}）：
 *       它们是客户端生成的，前缀是"这条轴不是设计器配的"的唯一标记。</li>
 *   <li>**孤儿单元格不报错**（{@code validateMatrixSubmission} 只遍历配置态+运行态的轴）：
 *       删掉一行后旧数据仍在 cells 里，报错会让存量单据再也提交不了。</li>
 *   <li>**必填只看合并后的轴**：行/列都被删光时不该因为"有必填"就要求填。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormMatrixRules {

    private final ObjectMapper json;

    public void validateMatrixSchema(com.fasterxml.jackson.databind.JsonNode node) {
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

    public void validateMatrixSubmission(com.fasterxml.jackson.databind.JsonNode node, Object rawValue) {
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

    public String matrixLabel(com.fasterxml.jackson.databind.JsonNode node) {
        return node.path("label").asText(node.path("id").asText("matrix_fill"));
    }

    private record MatrixAxisItem(String id, String label) {}
}
