package com.antflow.report;

import com.antflow.form.runtime.FormData;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表单台账导 CSV。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li>**带 UTF-8 BOM**：不加的话 Excel 用本地编码打开中文是乱码（这是最常见的"导出文件打不开"投诉）。</li>
 *   <li>**手写转义**：值里可能有逗号（地址）、引号、换行（多行文本）。CSV 的规则很短但很容易写漏，
 *       所以单独抽出来并有单测钉住。</li>
 *   <li>**时间按客户端时区**：库里是 timestamptz（UTC），直接格式化会差 8 小时。</li>
 *   <li>**动态列取所有行的并集**：同一张表单的历史数据字段可能不一致（改过版本），
 *       按第一行定表头会让后面的值错位。</li>
 * </ul>
 */
public final class FormDataCsv {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> FIXED_HEADERS =
        List.of("业务单号", "提交人", "工号", "部门", "提交时间", "状态");

    public static byte[] export(List<FormData> rows, ZoneOffset offset) {
        // 字段列：按首次出现的顺序收集所有行里出现过的字段名。
        Set<String> fieldLabels = new LinkedHashSet<>();
        for (FormData row : rows) {
            for (FormData.FieldValue value : row.getFieldValues()) {
                fieldLabels.add(label(value));
            }
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        List<String> headers = new ArrayList<>(FIXED_HEADERS);
        headers.addAll(fieldLabels);
        csv.append(headers.stream().map(FormDataCsv::cell).reduce((a, b) -> a + "," + b).orElse(""));
        csv.append("\r\n");
        for (FormData row : rows) {
            Map<String, String> values = new java.util.HashMap<>();
            for (FormData.FieldValue value : row.getFieldValues()) {
                values.put(label(value), text(value.value()));
            }
            List<String> line = new ArrayList<>(List.of(
                row.getBusinessNo() == null ? "" : row.getBusinessNo(),
                row.getCreatedByName() == null ? "" : row.getCreatedByName(),
                row.getCreatedByEmployeeNo() == null ? "" : row.getCreatedByEmployeeNo(),
                row.getCreatedByDeptName() == null ? "" : row.getCreatedByDeptName(),
                stamp(row.getCreatedAt(), offset),
                status(row.getStatus())));
            for (String fieldLabel : fieldLabels) {
                line.add(values.getOrDefault(fieldLabel, ""));
            }
            csv.append(line.stream().map(FormDataCsv::cell).reduce((a, b) -> a + "," + b).orElse(""));
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 字段名缺失时退回字段 id——总比一列空白好定位。 */
    private static String label(FormData.FieldValue value) {
        String name = value.fieldName();
        return name == null || name.isBlank() ? value.fieldId() : name;
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return list.stream().map(FormDataCsv::text).filter(item -> !item.isEmpty())
                .reduce((a, b) -> a + "、" + b).orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            // 检查项/明细这类是结构化的：直接用 Java 的 map.toString() 会写出
            // `{id=item-1, name=检查项1, images=[], status=na}` —— 又长又带着空值。
            // 这里只保留有内容的键值，用 `key=value` 串起来。
            return map.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !emptyValue(entry.getValue()))
                .map(entry -> entry.getKey() + "=" + text(entry.getValue()))
                .reduce((a, b) -> a + "; " + b).orElse("");
        }
        return String.valueOf(value);
    }

    /** 空值（null / 空白串 / 空数组）不进导出，免得一格里全是 `images=[], description=`。 */
    private static boolean emptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return text.isBlank();
        return value instanceof List<?> list && list.isEmpty();
    }

    private static String stamp(OffsetDateTime value, ZoneOffset offset) {
        return value == null ? "" : value.withOffsetSameInstant(offset).format(STAMP);
    }

    private static String status(String value) {
        if ("DRAFT".equals(value)) return "草稿";
        if ("SUBMITTED".equals(value)) return "已提交";
        return value == null ? "" : value;
    }

    /**
     * CSV 单元格转义：含逗号/引号/换行（或首尾空格）时用双引号包起来，内部的引号翻倍。
     * 不做这层，一个带逗号的地址就能让整行错列。
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
            || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ';
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private FormDataCsv() {
    }
}
