package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.importer.FormImportValueCodec.Prefetch;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 明细 sheet 的「一行一条」文本 → 与 JSON 解码**结构完全一致**的值。
 *
 * <p>为什么单独一类：这是"友好格式"的唯一落点。它必须与 {@link FormImportStructuredCodec}
 * 产出同样的数据结构（同样的键、同样的类型、图片恒为空数组），否则同一条记录从 Excel 导和从
 * JSON 导会落成两份不一样的数据，下游展示/统计各说各话。两边一致性由集成测试逐字节比对守住。
 *
 * <p>三条刻意的行为：
 * <ol>
 *   <li>**label 与 id 都接受**（检查项、结果、矩阵行）：补录数据来自人，人写的是名字；
 *       历史台账抄过来的是 id。只认一种的结果是"看着填对了却导不进去"。</li>
 *   <li>**错误消息带 sheet 名 + 明细表里的真实行号**：用户手上开着的是明细表，
 *       只说字段名他找不到是哪一行。</li>
 *   <li>**空行跳过**：同一行号下留空的行是排版习惯（例如两行之间空一行），不是错误。</li>
 * </ol>
 */
final class FormImportDetailValues {

    private FormImportDetailValues() { }

    /** checklist：每行一个检查项，产出 {@code [{id,status,images,description}]}。 */
    static List<Map<String, Object>> checklist(JsonNode field, String label, String sheet,
                                               List<Row> rows) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<String> seenItems = new LinkedHashSet<>();
        for (Row row : rows) {
            String item = row.text(FormImportDetailSpec.ITEM_ID);
            if (item.isBlank()) {
                throw invalid(label, sheet, row, "「检查项」不能为空，请填写检查项名称或编号");
            }
            String itemId = itemId(field, label, sheet, row, item);
            if (!seenItems.add(itemId)) {
                throw invalid(label, sheet, row,
                    "同一个检查项只能填一行，「" + item + "」重复了");
            }
            String statusText = row.text(FormImportDetailSpec.STATUS_ID);
            if (statusText.isBlank()) {
                throw invalid(label, sheet, row, "「结果」不能为空，"
                    + allowed(field.path("props").path("results"), "通过、不通过"));
            }
            String status = statusId(field, label, sheet, row, statusText);
            String description = row.text(FormImportDetailSpec.DESCRIPTION_ID);
            if (description.isBlank() && descriptionRequired(field, status)) {
                throw invalid(label, sheet, row, "结果选了「" + statusText
                    + "」时必须在「描述」里写明原因");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", itemId);
            entry.put("status", status);
            // 与 JSON 路径一致：导入不可能带图片，恒为空数组（省略会让下游拿 null 去 count）。
            entry.put("images", List.of());
            if (!description.isBlank()) entry.put("description", description);
            entries.add(entry);
        }
        return entries;
    }

    /** table_list：每行一条明细，子字段递归解码（人员、多选、外部选项都走原 codec）。 */
    static List<Map<String, Object>> tableList(JsonNode field, String label, String sheet,
                                               List<Row> rows, Prefetch prefetch,
                                               FormImportValueCodec codec) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            decodeChildren(field.path("children"), row, values, prefetch, codec, label, sheet);
            if (!values.isEmpty()) result.add(values);
        }
        return result;
    }

    private static void decodeChildren(JsonNode children, Row row, Map<String, Object> values,
                                       Prefetch prefetch, FormImportValueCodec codec,
                                       String label, String sheet) {
        if (children == null || !children.isArray()) return;
        for (JsonNode child : children) {
            String type = child.path("type").asText("");
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
                decodeChildren(child.path("children"), row, values, prefetch, codec, label, sheet);
                continue;
            }
            if ("description".equals(type)) continue;
            String id = child.path("id").asText("");
            if (id.isBlank()) continue;
            String text = row.text(id);
            if (text.isBlank()) continue;
            if (FormImportColumnCollector.isMedia(type)) {
                throw invalid(label, sheet, row, "「" + FormImportSupport.label(child)
                    + "」是附件类字段，Excel 无法携带文件，请改用表单填写");
            }
            try {
                Object value = codec.decode(child, text, prefetch);
                if (value != null) values.put(id, value);
            } catch (BizException error) {
                // 子字段自己的消息已经带了子字段名；补上"哪张表、哪一行"，否则用户只在主表里找。
                throw new BizException(error.getCode(), error.getMessage()
                    + "（明细表「" + sheet + "」第 " + row.sheetRow() + " 行）");
            }
        }
    }

    /** matrix_fill：产出 {@code {"cells":{行id:{列id:值}}}}，与 JSON 路径同形。 */
    static Map<String, Object> matrix(JsonNode field, String label, String sheet, List<Row> rows) {
        String cellType = field.path("props").path("cellType").asText("textarea");
        Map<String, Object> cells = new LinkedHashMap<>();
        Set<String> seenRows = new LinkedHashSet<>();
        for (Row row : rows) {
            String rowText = row.text(FormImportDetailSpec.MATRIX_ROW_ID);
            if (rowText.isBlank()) {
                throw invalid(label, sheet, row, "「行」不能为空，"
                    + allowed(field.path("props").path("rows"), "第一行"));
            }
            String rowId = rowId(field, label, sheet, row, rowText);
            if (!seenRows.add(rowId)) {
                throw invalid(label, sheet, row, "矩阵的同一行只能填一行，「" + rowText + "」重复了");
            }
            Map<String, Object> line = new LinkedHashMap<>();
            for (Map.Entry<String, String> cell : row.cells().entrySet()) {
                if (!isMatrixColumn(field, cell.getKey()) || cell.getValue().isBlank()) continue;
                line.put(cell.getKey(), matrixCell(label, sheet, row, cellType, cell.getValue()));
            }
            if (!line.isEmpty()) cells.put(rowId, line);
        }
        if (cells.isEmpty()) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cells", cells);
        return value;
    }

    private static Object matrixCell(String label, String sheet, Row row, String cellType,
                                     String text) {
        if (!"number".equals(cellType)) return text;
        try {
            return FormImportScalarCodec.number(label, text);
        } catch (BizException error) {
            throw invalid(label, sheet, row, "数值单元格只能填数字，现在填的是「" + text + "」");
        }
    }

    private static boolean isMatrixColumn(JsonNode field, String columnId) {
        if (columnId == null || columnId.isBlank()) return false;
        for (JsonNode column : field.path("props").path("columns")) {
            if (columnId.equals(column.path("id").asText(""))) return true;
        }
        return false;
    }

    private static String itemId(JsonNode field, String label, String sheet, Row row,
                                 String text) {
        JsonNode items = field.path("props").path("items");
        if (!items.isArray() || items.isEmpty()) return text;
        for (JsonNode item : items) {
            if (text.equals(item.path("id").asText(""))) return text;
        }
        for (JsonNode item : items) {
            if (text.equals(item.path("label").asText(""))) return item.path("id").asText(text);
        }
        throw invalid(label, sheet, row, "「检查项」" + allowed(items, "检查项1")
            + "；现在填的是「" + text + "」");
    }

    private static String statusId(JsonNode field, String label, String sheet, Row row,
                                   String text) {
        JsonNode results = field.path("props").path("results");
        if (!results.isArray() || results.isEmpty()) return text;
        for (JsonNode result : results) {
            if (text.equals(result.path("id").asText(""))) return text;
        }
        for (JsonNode result : results) {
            if (text.equals(result.path("label").asText(""))) {
                return result.path("id").asText(text);
            }
        }
        throw invalid(label, sheet, row, "「结果」" + allowed(results, "通过、不通过")
            + "；现在填的是「" + text + "」");
    }

    private static String rowId(JsonNode field, String label, String sheet, Row row, String text) {
        JsonNode rows = field.path("props").path("rows");
        if (!rows.isArray() || rows.isEmpty()) return text;
        for (JsonNode item : rows) {
            if (text.equals(item.path("id").asText(""))) return text;
        }
        for (JsonNode item : rows) {
            if (text.equals(item.path("label").asText(""))) return item.path("id").asText(text);
        }
        throw invalid(label, sheet, row, "「行」" + allowed(rows, "第一行")
            + "；现在填的是「" + text + "」");
    }

    private static boolean descriptionRequired(JsonNode field, String status) {
        JsonNode props = field.path("props");
        if (!props.path("allowDescription").asBoolean(true)) return false;
        return props.path("descriptionRequiredByResult").path(status).asBoolean(false);
    }

    /** 把配置里的候选拼成「只能填：A、B」；超过 8 项只列前几个，避免消息比屏幕还长。 */
    private static String allowed(JsonNode items, String fallback) {
        List<String> labels = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String label = item.path("label").asText("");
                if (!label.isBlank()) labels.add(label);
            }
        }
        if (labels.isEmpty()) return "只能填：" + fallback;
        String joined = String.join("、", labels.size() > 8 ? labels.subList(0, 8) : labels);
        return "只能填：" + joined + (labels.size() > 8 ? " 等" : "");
    }

    private static BizException invalid(String label, String sheet, Row row, String message) {
        return new BizException("IMPORT_VALUE_INVALID",
            "「" + label + "」的明细表「" + sheet + "」第 " + row.sheetRow() + " 行：" + message);
    }

    /** 明细表里的一行：{@code sheetRow} 是它在明细表里的真实行号；{@code cells} 以第 2 行的 id 为键。 */
    record Row(int sheetRow, Map<String, String> cells) {

        String text(String columnId) {
            String value = cells.get(columnId);
            return value == null ? "" : value.trim();
        }
    }
}
