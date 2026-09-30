package com.antflow.report;

import com.antflow.form.runtime.FormData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表单台账导出：CSV 与 Excel 共用同一份「列 + 值」模型。
 *
 * <p>刻意的选择：
 * <ul>
 *   <li>**列身份是 fieldId，标签只作表头**：两个不同字段可以叫同一个标签（改过版本的表单就是），
 *       拿标签当身份会互相覆盖、同一字段跨版本还会拆成两列。</li>
 *   <li>**值取后端已经解析好的 detailText**（{@link com.antflow.form.runtime.FormValueDisplay}）：
 *       导出与页面显示同一份文本，不会出现页面写「车削」、导出来是 `option_1`。</li>
 *   <li>**CSV 带 UTF-8 BOM**：不加的话 Excel 用本地编码打开中文是乱码。</li>
 *   <li>**公式注入防护**：`= + - @`（含前导空白之后）开头的值会被 Excel 当公式执行，字段值、
 *       姓名、部门名都能进 CSV，所以统一前置单引号当文本。</li>
 *   <li>**Excel 全 STRING 单元格**：工号 `000003` 不吃掉前导零，也不会把 `=1+1` 存成公式。</li>
 *   <li>**时间按客户端时区**：库里是 timestamptz（UTC），直接格式化会差 8 小时。</li>
 * </ul>
 */
public final class FormDataExport {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> FIXED_HEADERS =
        List.of("业务单号", "提交人", "工号", "部门", "提交时间", "状态");
    /** Excel 单元格字符上限，超过会直接抛异常。 */
    private static final int MAX_CELL_CHARS = 32767;
    /** 列宽上限（字符数）：再宽也没人看，反而把别的列挤出屏幕。 */
    private static final int MAX_COLUMN_CHARS = 60;
    /** 危险开头（前导空白也算）：`\s` 已覆盖制表符与 CR。 */
    private static final Pattern FORMULA_PREFIX = Pattern.compile("^\\s*[=+\\-@]");

    /** 列与值：表头 + 每行同宽的字符串。CSV 与 Excel 都只认这个。 */
    public record Model(List<String> headers, List<List<String>> rows) { }

    public static Model model(List<FormData> rows, ZoneOffset offset) {
        // 字段列按 fieldId 收集、按首次出现排序：标签只当表头（同名不合并、跨版本不拆列）。
        Map<String, String> fieldHeaders = new LinkedHashMap<>();
        for (FormData row : rows) {
            for (FormData.FieldValue value : row.getFieldValues()) {
                fieldHeaders.putIfAbsent(value.fieldId(), label(value));
            }
        }
        List<String> headers = new ArrayList<>(FIXED_HEADERS);
        headers.addAll(fieldHeaders.values());

        List<List<String>> body = new ArrayList<>();
        for (FormData row : rows) {
            Map<String, String> byFieldId = new LinkedHashMap<>();
            for (FormData.FieldValue value : row.getFieldValues()) {
                byFieldId.put(value.fieldId(), singleLine(value.detailText()));
            }
            List<String> line = new ArrayList<>(List.of(
                orEmpty(row.getBusinessNo()),
                orEmpty(row.getCreatedByName()),
                orEmpty(row.getCreatedByEmployeeNo()),
                orEmpty(row.getCreatedByDeptName()),
                stamp(row.getCreatedAt(), offset),
                status(row.getStatus())));
            for (String fieldId : fieldHeaders.keySet()) {
                line.add(byFieldId.getOrDefault(fieldId, ""));
            }
            body.add(line);
        }
        return new Model(headers, body);
    }

    /** CSV：BOM + 手写转义（值里可能有逗号、引号、换行）。 */
    public static byte[] csv(Model model) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(join(model.headers())).append("\r\n");
        for (List<String> row : model.rows()) {
            csv.append(join(row)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Excel：SXSSF 流式写（只留一窗口的行在内存里——1 万行的 XSSF 能把堆吃光），
     * 结束时必须 {@code dispose()} 清临时文件。
     */
    public static byte[] xlsx(Model model) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("台账");
            writeRow(sheet, 0, model.headers());
            int[] widths = new int[model.headers().size()];
            for (int index = 0; index < model.headers().size(); index++) {
                widths[index] = model.headers().get(index).length();
            }
            for (int index = 0; index < model.rows().size(); index++) {
                List<String> line = model.rows().get(index);
                writeRow(sheet, index + 1, line);
                for (int column = 0; column < line.size(); column++) {
                    widths[column] = Math.max(widths[column], line.get(column).length());
                }
            }
            for (int column = 0; column < widths.length; column++) {
                sheet.setColumnWidth(column, Math.min(widths[column] + 2, MAX_COLUMN_CHARS) * 256);
            }
            workbook.write(out);
            workbook.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出 Excel 失败", e);
        }
    }

    private static void writeRow(Sheet sheet, int index, List<String> values) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.size(); column++) {
            Cell cell = row.createCell(column);
            String value = values.get(column);
            // 全 STRING 单元格：工号保住前导零，`=1+1` 也不会被存成公式。
            cell.setCellValue(value.length() > MAX_CELL_CHARS
                ? value.substring(0, MAX_CELL_CHARS) : value);
        }
    }

    private static String join(List<String> values) {
        return values.stream().map(FormDataExport::cell).reduce((a, b) -> a + "," + b).orElse("");
    }

    /**
     * 详情里的换行压成 `；`：一个格里留换行，用户看到的是一行被撑开的表；而且不少工具对单元格内
     * 换行处理不一致。导出是"看一眼/筛一下"的用途，不是二次导入的数据源。
     */
    private static String singleLine(String detail) {
        return detail == null ? "" : detail.replace("\r\n", "；").replace("\n", "；");
    }

    /** 字段名缺失时退回字段 id——总比一列空白好定位。 */
    private static String label(FormData.FieldValue value) {
        String name = value.fieldName();
        return name == null || name.isBlank() ? value.fieldId() : name;
    }

    private static String stamp(OffsetDateTime value, ZoneOffset offset) {
        return value == null ? "" : value.withOffsetSameInstant(offset).format(STAMP);
    }

    private static String status(String value) {
        if ("DRAFT".equals(value)) return "草稿";
        if ("SUBMITTED".equals(value)) return "已提交";
        return orEmpty(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * CSV 单元格转义：危险开头前置单引号（Excel 当文本读），含逗号/引号/换行（或首尾空格）时
     * 用双引号包起来、内部的引号翻倍。不做这层，一个带逗号的地址就能让整行错列。
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String safe = FORMULA_PREFIX.matcher(value).find() ? "'" + value : value;
        boolean needsQuotes = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
            || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0
            || safe.charAt(0) == ' ' || safe.charAt(safe.length() - 1) == ' ';
        if (!needsQuotes) return safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private FormDataExport() {
    }
}
