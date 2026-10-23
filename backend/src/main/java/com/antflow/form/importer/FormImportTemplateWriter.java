package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.importer.FormImportColumnCollector.Column;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 三张 sheet 的 POI 渲染：数据 / {@code __antflow_meta} / 填写说明（+ 隐藏的 {@code __dict}）。
 *
 * <p>模板格式的常量（sheet 名、模板版本、保留列）定义在这里：它们是**渲染器的输出契约**，
 * 解析端按同一份常量对账。{@link FormImportTemplateService} 只是把它们原样再暴露一次，
 * 这样新类不必反向引用门面。
 *
 * <p>两处一旦改错就会让"下下来的模板导不回来"：
 * <ol>
 *   <li>第二行是给程序看的字段 id，必须 {@code setZeroHeight(true)} 隐藏但**仍可编辑**
 *       （取消隐藏能看到）；改一个 id 就能让导入把值写进别的字段。</li>
 *   <li>下拉校验的取值区间从第 3 行开始（0 基 2），上限与解析器一致——超出部分本来也会被拒绝。</li>
 * </ol>
 */
@Component
public class FormImportTemplateWriter {

    public static final String SHEET_DATA = "数据";
    public static final String SHEET_META = "__antflow_meta";
    public static final String SHEET_DICT = "__dict";
    public static final String SHEET_HELP = "填写说明";

    /** 模板版本：表结构/元数据形状变了才 +1；导入端只认 1。 */
    public static final int TEMPLATE_VERSION = 1;

    /** 「业务单号」是保留列：不是表单字段，写 import_key 而不是 business_no（原因见 V62）。 */
    public static final String KEY_COLUMN_ID = "__import_key";
    public static final String KEY_COLUMN_LABEL = "业务单号";

    private final ObjectMapper json;

    public FormImportTemplateWriter(ObjectMapper json) {
        this.json = json;
    }

    /** 生成模板字节。调用方负责鉴权、审计与列数上限（本类只做渲染）。 */
    byte[] render(FormDefinition form, List<Column> columns) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try {
            CellStyle header = headerStyle(workbook);
            CellStyle fieldIdStyle = fieldIdStyle(workbook);
            SXSSFSheet data = workbook.createSheet(SHEET_DATA);
            writeHeader(data, columns, header, fieldIdStyle);
            SXSSFSheet dict = workbook.createSheet(SHEET_DICT);
            writeDictionary(dict, columns);
            writeMetaSheet(workbook, form, columns);
            writeHelpSheet(workbook, columns, header);
            addValidations(data, columns);
            // 隐藏辅助表：用户不该编辑它们，否则改一个字段 id 就能让导入把值写进别的字段。
            workbook.setSheetVisibility(workbook.getSheetIndex(SHEET_META), SheetVisibility.HIDDEN);
            workbook.setSheetVisibility(workbook.getSheetIndex(SHEET_DICT), SheetVisibility.HIDDEN);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (java.io.IOException error) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "模板生成失败，请重试");
        } finally {
            try {
                workbook.close();
            } catch (java.io.IOException ignored) {
                // 关不掉临时文件不影响已经写出的字节。
            }
            workbook.dispose();
        }
    }

    /** schema 的稳定摘要：模板与库里的定义必须一致，避免"旧模板往新表单里灌列"。 */
    public static String schemaHash(String schema) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    (schema == null ? "" : schema).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private void writeHeader(SXSSFSheet sheet, List<Column> columns, CellStyle header,
                             CellStyle fieldIdStyle) {
        Row labels = sheet.createRow(0);
        Row ids = sheet.createRow(1);
        writeCell(labels, 0, KEY_COLUMN_LABEL, header);
        writeCell(ids, 0, KEY_COLUMN_ID, fieldIdStyle);
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            int cell = index + 1;
            writeCell(labels, cell, column.label(), header);
            writeCell(ids, cell, column.fieldId(), fieldIdStyle);
            // 表头两行之后才是数据；宽度按标签长度估，中文按 2 个字符算。
            sheet.setColumnWidth(cell, Math.min(40, Math.max(12, displayWidth(column.label()) + 4)) * 256);
        }
        sheet.setColumnWidth(0, 22 * 256);
        // 第二行是给程序看的字段 id，藏起来但仍可编辑（取消隐藏时能看到）。
        ids.setZeroHeight(true);
        sheet.createFreezePane(0, 2);
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length(); index++) {
            width += value.charAt(index) > 127 ? 2 : 1;
        }
        return width;
    }

    private void writeDictionary(SXSSFSheet dict, List<Column> columns) {
        int columnIndex = 0;
        for (Column column : columns) {
            if (column.dictionary().isEmpty()) continue;
            for (int row = 0; row < column.dictionary().size(); row++) {
                Row target = dict.getRow(row);
                if (target == null) target = dict.createRow(row);
                target.createCell(columnIndex).setCellValue(column.dictionary().get(row));
            }
            columnIndex++;
        }
    }

    private void addValidations(SXSSFSheet data, List<Column> columns) {
        DataValidationHelper helper = data.getDataValidationHelper();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            if (column.dictionary().isEmpty()) continue;
            int dictColumn = dictionaryColumn(columns, index);
            String formula = "'" + SHEET_DICT + "'!$" + columnName(dictColumn) + "$1:$"
                + columnName(dictColumn) + "$" + column.dictionary().size();
            DataValidationConstraint constraint = helper.createFormulaListConstraint(formula);
            // 数据从第 3 行开始（0 基 2）；上限与解析器一致，超出部分本来也会被拒绝。
            CellRangeAddressList range = new CellRangeAddressList(
                2, com.antflow.form.options.OptionTableImporter.MAX_ROWS + 1, index + 1, index + 1);
            DataValidation validation = helper.createValidation(constraint, range);
            validation.setShowErrorBox(true);
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            validation.createErrorBox("取值不在模板选项内", "请从下拉列表选择，或核对字段的选项配置");
            data.addValidationData(validation);
        }
    }

    private static int dictionaryColumn(List<Column> columns, int columnIndex) {
        int result = 0;
        for (int index = 0; index < columnIndex; index++) {
            if (!columns.get(index).dictionary().isEmpty()) result++;
        }
        return result;
    }

    /** POI 的列名工具（A..Z, AA..）：刻意不用 CellReference 以外的东西，避免 50 列时手写错位。 */
    private static String columnName(int index) {
        return org.apache.poi.ss.util.CellReference.convertNumToColString(index);
    }

    private void writeMetaSheet(SXSSFWorkbook workbook, FormDefinition form, List<Column> columns) {
        SXSSFSheet meta = workbook.createSheet(SHEET_META);
        ObjectNode root = json.createObjectNode();
        root.put("templateVersion", TEMPLATE_VERSION);
        root.put("formId", form.getId());
        root.put("formVersion", form.getVersion() == null ? 0 : form.getVersion());
        root.put("schemaHash", schemaHash(form.getSchema()));
        var fields = root.putArray("fields");
        for (Column column : columns) {
            ObjectNode item = fields.addObject();
            item.put("fieldId", column.fieldId());
            item.put("label", column.label());
            item.put("type", column.type());
            item.put("order", column.order());
            item.put("required", column.required());
        }
        try {
            meta.createRow(0).createCell(0).setCellValue(json.writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "模板元数据生成失败");
        }
    }

    private void writeHelpSheet(SXSSFWorkbook workbook, List<Column> columns, CellStyle header) {
        SXSSFSheet help = workbook.createSheet(SHEET_HELP);
        Row head = help.createRow(0);
        String[] titles = {"字段名", "类型", "是否必填", "填写方式"};
        for (int index = 0; index < titles.length; index++) writeCell(head, index, titles[index], header);
        help.setColumnWidth(0, 26 * 256);
        help.setColumnWidth(1, 14 * 256);
        help.setColumnWidth(2, 10 * 256);
        help.setColumnWidth(3, 70 * 256);
        int rowIndex = 1;
        rowIndex = helpRow(help, rowIndex, KEY_COLUMN_LABEL, "文本", "否",
            "可选。填了则按它判断重复（同一张表单内唯一），并原样记入导入键；不会写入系统业务单号");
        for (Column column : columns) {
            Row row = help.createRow(rowIndex++);
            writeCell(row, 0, column.label(), null);
            writeCell(row, 1, typeLabel(column.type()), null);
            writeCell(row, 2, column.required() ? "是" : "否", null);
            writeCell(row, 3, howToFill(column), null);
        }
    }

    private int helpRow(SXSSFSheet help, int rowIndex, String name, String type, String required,
                        String how) {
        Row row = help.createRow(rowIndex);
        writeCell(row, 0, name, null);
        writeCell(row, 1, type, null);
        writeCell(row, 2, required, null);
        writeCell(row, 3, how, null);
        return rowIndex + 1;
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "text" -> "单行文本";
            case "textarea" -> "多行文本";
            case "number" -> "数字";
            case "money" -> "金额";
            case "date" -> "日期";
            case "time" -> "时间";
            case "date_range" -> "日期区间";
            case "switch" -> "开关";
            case "select" -> "下拉单选";
            case "radio" -> "单选";
            case "multi_select" -> "下拉多选";
            case "checkbox" -> "多选";
            case "user_picker" -> "人员";
            case "dept_picker" -> "部门";
            case "location" -> "定位";
            case "table_list" -> "明细表";
            case "checklist" -> "检查项";
            case "matrix_fill" -> "矩阵填空";
            case "search" -> "搜索";
            case "scan_code" -> "扫码";
            default -> type;
        };
    }

    private static String howToFill(Column column) {
        String type = column.type();
        return switch (type) {
            case "text", "search", "scan_code" -> "直接输入文本，不要带公式";
            case "textarea" -> "直接输入，可换行（Alt+Enter）";
            case "number" -> "输入数字，不要带千分位（如 1200.50）";
            case "money" -> "输入金额数字，不要带千分位与货币符号";
            case "date" -> "yyyy-MM-dd，如 2024-01-31";
            case "time" -> "HH:mm 或 HH:mm:ss";
            case "date_range" -> "起~止，如 2024-01-01~2024-01-31";
            case "switch" -> "填「是」或「否」";
            case "select", "radio" -> column.dictionary().isEmpty()
                ? "填写选项名称（label）或选项值" : "填写选项名称：" + String.join("、", column.dictionary());
            case "multi_select", "checkbox" -> "多个值用「、」分隔；也可用 , ， ; ；";
            case "user_picker" -> "填工号或姓名；多个用「、」分隔；重名时必须写工号或 id:123";
            case "dept_picker" -> "填部门名称或全路径（总部/研发部）；多个用「、」分隔";
            case "location" -> "纬度,经度，如 30.12,120.65";
            case "table_list" -> "JSON 数组，每个元素是一行："
                + "[{\"子字段id\":\"值\"}]（字段 id 见模板第二行）";
            case "checklist" -> "JSON 数组：[{\"id\":\"检查项id\",\"status\":\"结果id\",\"description\":\"说明\"}]";
            case "matrix_fill" -> "JSON 对象：{\"cells\":{\"行id\":{\"列id\":\"值\"}}}";
            default -> "";
        };
    }

    private static void writeCell(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellType(CellType.STRING);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBottomBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        return style;
    }

    private static CellStyle fieldIdStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 8);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
