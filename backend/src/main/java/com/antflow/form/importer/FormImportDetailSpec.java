package com.antflow.form.importer;

import com.antflow.form.FieldTypeCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「明细 sheet」的格式契约：一个结构化字段（checklist / table_list / matrix_fill）对应一张独立工作表。
 *
 * <p>为什么单独一类：模板渲染（{@link FormImportDetailSheetWriter}）与导入解析
 * （{@link FormImportDetailSheets}）必须对"表头叫什么、第二行写哪个 id、哪一列是行号"
 * 有**同一份**认知；两边各写一遍常量，改一处就会让新模板导不回来，而且错在用户身上。
 *
 * <p>三段结构（与主 sheet 的两行表头同构）：
 * <pre>
 * 第 1 行：行号 | 检查项 | 结果 | 描述        ← 人看的列头
 * 第 2 行：__row | __item | __status | __description  ← 机器对齐，隐藏
 * 第 3 行起：数据
 * </pre>
 *
 * <p>**「行号」是唯一关联键**（主 sheet「数据」里该条记录的真实 Excel 行号，数据从 3 开始）。
 * 刻意不用业务单号：业务单号是可选列，用户可能不填、可能重复，而"第几条记录"永远存在且不会歧义。
 *
 * <p>sheet 名用字段 label（截断到 28 字符，给 {@code (2)} 后缀留位置；Excel 上限 31 字符），
 * 与保留名冲突时同样加后缀。
 *
 * <p>**别改 sheet 名**：{@code __antflow_meta} 里的 {@code detailSheets} 记录的是生成时的名字，
 * 解析时按这个名字去 {@code getSheet(name)}。用户把「检查项」改成「检查项明细」后取不到，
 * 只会得到一条 warning、该字段的明细**全部不被读取**，最终通常表现为「请完成检查项」。
 * （早期注释写成"改名不影响"，与实际行为不符，已纠正。）
 */
final class FormImportDetailSpec {

    /** 第 1 列固定是「行号」。 */
    static final String ROW_HEADER = "行号";
    static final String ROW_ID = "__row";

    /** checklist 的三列：检查项 / 结果 / 描述。 */
    static final String ITEM_HEADER = "检查项";
    static final String ITEM_ID = "__item";
    static final String STATUS_HEADER = "结果";
    static final String STATUS_ID = "__status";
    static final String DESCRIPTION_HEADER = "描述";
    static final String DESCRIPTION_ID = "__description";

    /** matrix_fill 的第 2 列是矩阵行名。 */
    static final String MATRIX_ROW_HEADER = "行";
    static final String MATRIX_ROW_ID = "__row_key";

    /** Excel 的 sheet 名上限是 31；留 3 个字符给 {@code (2)}..{@code (9)}。 */
    static final int MAX_SHEET_NAME = 28;

    /** 只有这三种字段有明细 sheet；其余字段仍然是一列一个值。 */
    static final Set<String> STRUCTURED_TYPES = Set.of("checklist", "table_list", "matrix_fill");

    /** Excel sheet 名不允许的字符（以及控制字符）。 */
    private static final String ILLEGAL_SHEET_CHARS = "[]:*?/\\\\";

    private FormImportDetailSpec() { }

    /** 明细 sheet 的一列：{@code id} 写在第 2 行（机器对齐），{@code header} 写在第 1 行（人看）。 */
    record Column(String id, String header) { }

    /**
     * 一个字段对应的明细 sheet。
     *
     * @param field           schema 里的字段节点（解码时要用它的 props/children）
     * @param sheetName       已经处理过截断、去重与保留名冲突的最终 sheet 名
     */
    record Spec(String fieldId, String label, String kind, String sheetName, JsonNode field,
                List<Column> columns) {

        /** 第 1 行完整表头（含开头的「行号」）。 */
        List<String> headers() {
            List<String> headers = new ArrayList<>(columns.size() + 1);
            headers.add(ROW_HEADER);
            columns.forEach(column -> headers.add(column.header()));
            return headers;
        }

        /** 第 2 行完整 id 列表（含开头的 {@code __row}）。 */
        List<String> ids() {
            List<String> ids = new ArrayList<>(columns.size() + 1);
            ids.add(ROW_ID);
            columns.forEach(column -> ids.add(column.id()));
            return ids;
        }

        /** 某一列的下拉候选（只取 label：界面上用户看到的就是它）。没有就返回空。 */
        List<String> dictionary(String columnId) {
            if (STATUS_ID.equals(columnId)) return labels(field.path("props").path("results"));
            if (ITEM_ID.equals(columnId)) return labels(field.path("props").path("items"));
            if (MATRIX_ROW_ID.equals(columnId)) return labels(field.path("props").path("rows"));
            return List.of();
        }
    }

    static boolean isStructured(String type) {
        return STRUCTURED_TYPES.contains(type);
    }

    /**
     * 按 schema 顺序生成全部明细 sheet。命名去重是**全局**的（同一次模板里两张 sheet 不能同名），
     * 因此必须一次算完，不能由每个字段各自决定。
     */
    static List<Spec> build(JsonNode schema) {
        List<Spec> specs = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        // 保留名先占位：字段恰好叫「数据」时不能把主 sheet 顶掉。
        used.add(key(FormImportTemplateWriter.SHEET_DATA));
        used.add(key(FormImportTemplateWriter.SHEET_META));
        used.add(key(FormImportTemplateWriter.SHEET_HELP));
        used.add(key(FormImportTemplateWriter.SHEET_DICT));
        collect(schema, specs, used);
        return specs;
    }

    private static void collect(JsonNode nodes, List<Spec> out, Set<String> used) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
                collect(node.path("children"), out, used);
                continue;
            }
            if (!STRUCTURED_TYPES.contains(type)) continue;
            String fieldId = node.path("id").asText("");
            if (fieldId.isBlank()) continue;
            String label = FormImportSupport.label(node);
            out.add(new Spec(fieldId, label, type, sheetName(label, used), node,
                columns(type, node)));
        }
    }

    private static List<Column> columns(String type, JsonNode field) {
        return switch (type) {
            case "checklist" -> List.of(
                new Column(ITEM_ID, ITEM_HEADER),
                new Column(STATUS_ID, STATUS_HEADER),
                new Column(DESCRIPTION_ID, DESCRIPTION_HEADER));
            case "matrix_fill" -> matrixColumns(field);
            default -> tableListColumns(field.path("children"), new ArrayList<>());
        };
    }

    private static List<Column> matrixColumns(JsonNode field) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column(MATRIX_ROW_ID, MATRIX_ROW_HEADER));
        for (JsonNode column : field.path("props").path("columns")) {
            String id = column.path("id").asText("");
            if (id.isBlank()) continue;
            String label = column.path("label").asText(id);
            columns.add(new Column(id, truncateHeader(label, id)));
        }
        return columns;
    }

    /** 明细表的子字段：容器平铺；展示字段与媒体字段没有可填的值，不出列。 */
    private static List<Column> tableListColumns(JsonNode children, List<Column> out) {
        if (children == null || !children.isArray()) return out;
        for (JsonNode child : children) {
            String type = child.path("type").asText("");
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
                tableListColumns(child.path("children"), out);
                continue;
            }
            if ("description".equals(type) || FormImportColumnCollector.isMedia(type)) continue;
            String id = child.path("id").asText("");
            if (id.isBlank()) continue;
            String label = child.path("label").asText(id);
            out.add(new Column(id, truncateHeader(label, id)));
        }
        return out;
    }

    private static String truncateHeader(String label, String fallback) {
        String header = label == null || label.isBlank() ? fallback : label;
        // 解析器对表头有 100 字符上限；超了模板就永远导不回来。
        return header.length() > 100 ? header.substring(0, 100) : header;
    }

    /** 生成唯一 sheet 名：截断 → 去非法字符 → 与已用名（含保留名）冲突就加 {@code (2)}。 */
    static String sheetName(String label, Set<String> used) {
        String base = sanitize(label);
        if (base.isBlank()) base = "明细";
        base = truncate(base, MAX_SHEET_NAME);
        String candidate = base;
        for (int index = 2; !used.add(key(candidate)); index++) {
            String suffix = "(" + index + ")";
            candidate = truncate(base, MAX_SHEET_NAME - suffix.length()) + suffix;
        }
        return candidate;
    }

    private static String sanitize(String label) {
        String value = label == null ? "" : label.trim();
        for (char illegal : ILLEGAL_SHEET_CHARS.toCharArray()) {
            value = value.replace(illegal, '_');
        }
        StringBuilder clean = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= 0x20) clean.append(current);
        }
        return clean.toString().trim();
    }

    /** Excel 判断 sheet 重名时不分大小写；这里用同一个口径。 */
    private static String key(String sheetName) {
        return sheetName.toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static List<String> labels(JsonNode items) {
        List<String> labels = new ArrayList<>();
        if (items == null || !items.isArray()) return labels;
        for (JsonNode item : items) {
            String label = item.path("label").asText("");
            if (!label.isBlank()) labels.add(label);
        }
        return labels;
    }
}
