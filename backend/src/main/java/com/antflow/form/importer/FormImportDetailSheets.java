package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.importer.FormImportValueCodec.Prefetch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 明细 sheet 的读取与归组：「行号 → 一组明细行」，解码交给 {@link FormImportDetailValues}。
 *
 * <p>三条刻意不猜的地方：
 * <ol>
 *   <li><b>只认 {@code __antflow_meta.detailSheets} 记录的 sheet</b>：用户可能把表改名、复制一份，
 *       靠"sheet 名像字段名"去找会把别的表当成明细表。元数据缺失 = 老模板，直接走原路径。</li>
 *   <li><b>行号必须是本文件真实存在的数据行</b>：填 40 但文件只有 3 行时，如果"尽力而为"地
 *       跳过，用户会以为导进去了。这里当场拒绝并指出是「数据」表的哪几行。</li>
 *   <li><b>行号留空承接上一行</b>：手工补录时连续几个检查项写同一个行号很啰嗦，留空是最自然的填法。</li>
 * </ol>
 *
 * <p>与主 sheet 的优先级（在 {@link FormImportParser} 里执行）：主 sheet 该列**有值**就以它为准
 * （老模板/老用户的 JSON 继续有效），只有为空时才用明细表。这样同一份模板可以被两种写法混合填写。
 */
@Component
public class FormImportDetailSheets {

    private final FormImportValueCodec codec;
    private final ObjectMapper json;

    public FormImportDetailSheets(FormImportValueCodec codec, ObjectMapper json) {
        this.codec = codec;
        this.json = json;
    }

    /**
     * 读取全部明细表。
     *
     * @param dataRows 「数据」表里真实存在的数据行号（3..N）；行号不属于它一律拒绝
     */
    Parsed parse(JsonNode schema, JsonNode meta, byte[] bytes, Set<Integer> dataRows) {
        JsonNode declared = meta == null ? null : meta.path("detailSheets");
        if (declared == null || !declared.isArray() || declared.isEmpty()) {
            // 老模板没有 detailSheets：完全不碰工作簿，零影响。
            return new Parsed(Map.of(), List.of());
        }
        Map<String, JsonNode> fields = fieldsById(schema);
        List<String> warnings = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (JsonNode item : declared) {
                String sheetName = item.path("sheet").asText("");
                String fieldId = item.path("fieldId").asText("");
                String kind = item.path("kind").asText("");
                JsonNode field = fields.get(fieldId);
                if (sheetName.isBlank() || field == null || kind.isBlank()) continue;
                Sheet sheet = workbook.getSheet(sheetName);
                String label = FormImportSupport.label(field);
                if (sheet == null) {
                    warnings.add("模板里声明的明细表「" + sheetName + "」不存在，"
                        + "「" + label + "」将只按主表内容读取");
                    continue;
                }
                groups.put(fieldId, new Group(sheetName, kind, field,
                    readRows(sheet, label, sheetName, dataRows)));
            }
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException("IMPORT_FILE_UNREADABLE",
                "无法解析明细工作表，请确认文件未加密且格式正确");
        }
        return new Parsed(groups, warnings);
    }

    private Map<Integer, List<FormImportDetailValues.Row>> readRows(Sheet sheet, String label,
                                                                    String sheetName,
                                                                    Set<Integer> dataRows) {
        Map<Integer, String> ids = columnIds(sheet.getRow(1));
        // 第 2 行是机器对齐用的字段标识（第一列固定是 __row = 「行号」）。
        // 判据必须是"含 __row"，不能只判 ids.isEmpty()：用户把第 2 行整行删掉后数据会上移，
        // 那一行仍有内容但不是标识 → ids 非空 → 不报错 → 所有单元格按数据文本当列 id，
        // 于是明细静默全空，最后只在必填校验处冒出一句「请完成检查项」，用户根本想不到是表头被删了。
        if (!ids.containsValue(FormImportDetailSpec.ROW_ID)) {
            throw new BizException("IMPORT_TEMPLATE_INVALID",
                "明细表「" + sheetName + "」的第 2 行（字段标识行）被改动或删除，请重新下载模板填写");
        }
        DataFormatter formatter = formatter();
        Map<Integer, List<FormImportDetailValues.Row>> grouped = new LinkedHashMap<>();
        int current = 0;
        for (int index = 2; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            Map<String, String> cells = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> column : ids.entrySet()) {
                Cell cell = row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String text = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (!text.isEmpty()) cells.put(column.getValue(), text);
            }
            if (cells.isEmpty()) continue;
            int sheetRow = index + 1;
            current = dataRow(cells.remove(FormImportDetailSpec.ROW_ID), label, sheetName,
                sheetRow, current, dataRows);
            grouped.computeIfAbsent(current, key -> new ArrayList<>())
                .add(new FormImportDetailValues.Row(sheetRow, cells));
        }
        return grouped;
    }

    /** 行号解析：空 = 承接上一行；非法或指向不存在的数据行 = 当场拒绝。 */
    private static int dataRow(String text, String label, String sheetName, int sheetRow,
                               int previous, Set<Integer> dataRows) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            if (previous > 0) return previous;
            throw invalid(label, sheetName, sheetRow,
                "请填写「行号」——也就是「数据」表里这条记录的行号（从 3 开始）");
        }
        int row;
        try {
            row = Integer.parseInt(value.endsWith(".0")
                ? value.substring(0, value.length() - 2) : value);
        } catch (NumberFormatException error) {
            throw invalid(label, sheetName, sheetRow,
                "「行号」必须是数字，现在填的是「" + value + "」");
        }
        if (!dataRows.contains(row)) {
            throw invalid(label, sheetName, sheetRow, "「行号」填的 " + row
                + " 在「数据」表里没有这条记录（本文件的数据行是 " + describeRows(dataRows) + "）");
        }
        return row;
    }

    private static String describeRows(Set<Integer> rows) {
        if (rows.isEmpty()) return "第 3 行起";
        Set<Integer> sorted = new TreeSet<>(rows);
        int first = sorted.iterator().next();
        int last = first;
        for (int row : sorted) last = Math.max(last, row);
        return first == last ? String.valueOf(first) : first + "-" + last;
    }

    private static Map<Integer, String> columnIds(Row row) {
        Map<Integer, String> ids = new LinkedHashMap<>();
        if (row == null) return ids;
        DataFormatter formatter = formatter();
        for (Cell cell : row) {
            String id = formatter.formatCellValue(cell).trim();
            if (!id.isEmpty()) ids.put(cell.getColumnIndex(), id);
        }
        return ids;
    }

    private static DataFormatter formatter() {
        // 公式单元格取缓存值：取到 =A1+B1 会把公式文本当成用户填写的内容。
        DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
        formatter.setUseCachedValuesForFormulaCells(true);
        return formatter;
    }

    private static BizException invalid(String label, String sheetName, int sheetRow,
                                        String message) {
        return new BizException("IMPORT_VALUE_INVALID",
            "「" + label + "」的明细表「" + sheetName + "」第 " + sheetRow + " 行：" + message);
    }

    private static Map<String, JsonNode> fieldsById(JsonNode schema) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        collectFields(schema, fields);
        return fields;
    }

    private static void collectFields(JsonNode nodes, Map<String, JsonNode> fields) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String id = node.path("id").asText("");
            if (!id.isBlank()) fields.putIfAbsent(id, node);
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER)
                .contains(node.path("type").asText(""))) {
                collectFields(node.path("children"), fields);
            }
        }
    }

    private record Group(String sheetName, String kind, JsonNode field,
                         Map<Integer, List<FormImportDetailValues.Row>> rows) { }

    /**
     * 解析结果。只在 {@link FormImportDetailSheets#parse} 内部构造；对外给解析器四个问题：
     * 这行有没有明细、在哪张表、值是什么、预取要扫哪些单元格。
     */
    final class Parsed {

        private final Map<String, Group> groups;
        private final List<String> warnings;

        private Parsed(Map<String, Group> groups, List<String> warnings) {
            this.groups = groups;
            this.warnings = List.copyOf(warnings);
        }

        List<String> warnings() {
            return warnings;
        }

        String sheet(String fieldId) {
            Group group = groups.get(fieldId);
            return group == null ? "" : group.sheetName();
        }

        boolean has(String fieldId, int dataRow) {
            Group group = groups.get(fieldId);
            return group != null && group.rows().containsKey(dataRow);
        }

        /** 该字段在该数据行上的值；没有明细行时返回 {@code null}（调用方会走必填校验）。 */
        Object decode(String fieldId, int dataRow, Prefetch prefetch) {
            Group group = groups.get(fieldId);
            if (group == null) return null;
            List<FormImportDetailValues.Row> rows = group.rows().get(dataRow);
            if (rows == null || rows.isEmpty()) return null;
            String label = FormImportSupport.label(group.field());
            Object value = switch (group.kind()) {
                case "checklist" ->
                    FormImportDetailValues.checklist(group.field(), label, group.sheetName(), rows);
                case "table_list" -> FormImportDetailValues.tableList(group.field(), label,
                    group.sheetName(), rows, prefetch, codec);
                case "matrix_fill" ->
                    FormImportDetailValues.matrix(group.field(), label, group.sheetName(), rows);
                default -> null;
            };
            return isEmpty(value) ? null : value;
        }

        /**
         * 预取用的"伪数据行"：明细表里的子字段（人员、外部选项）也要一次性查回来。
         * 这里把明细行拼成主 sheet 里那种 JSON 文本，复用 codec 已有的扫描规则，不另写一套。
         */
        List<Map<String, String>> prefetchRows() {
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map.Entry<String, Group> entry : groups.entrySet()) {
                if (!"table_list".equals(entry.getValue().kind())) continue;
                for (List<FormImportDetailValues.Row> cells : entry.getValue().rows().values()) {
                    if (cells.isEmpty()) continue;
                    List<Map<String, String>> items = new ArrayList<>();
                    cells.forEach(row -> items.add(new LinkedHashMap<>(row.cells())));
                    rows.add(Map.of(entry.getKey(), write(items)));
                }
            }
            return rows;
        }

        private String write(Object value) {
            try {
                return json.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new BizException("IMPORT_VALUE_INVALID", "明细表内容无法读取，请重新下载模板填写");
            }
        }
    }

    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof java.util.Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return false;
    }
}
