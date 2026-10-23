package com.antflow.form.importer;

import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 明细 sheet 的 POI 渲染：第 1 行人看的表头、第 2 行机器对齐的 id（隐藏）、第 3 行起数据。
 *
 * <p>为什么从 {@link FormImportTemplateWriter} 拆出来：那个类已经在 300 行上下，
 * 而"明细表怎么画"和"主表怎么画"是两个独立变化点（明细列随控件族变，主表列随字段清单变）。
 *
 * <p>三条与主表**刻意一致**的细节，少一条用户就会在另一张表上踩坑：
 * <ol>
 *   <li>第 2 行 {@code setZeroHeight(true)} 隐藏但**仍可编辑**：取消隐藏就能看到字段 id。</li>
 *   <li>冻结首行（数据从第 3 行开始，但第 2 行是隐藏的 id 行，冻 2 行会让界面空出一条）。</li>
 *   <li>下拉校验的区间上限与解析器一致：超出部分本来也会被拒绝。</li>
 * </ol>
 *
 * <p>Excel 的显式下拉列表总长度不能超过 255 字符，所以只在"候选 ≤20 项且拼起来放得下"时加。
 * 加不上下拉不影响导入——解析端仍然按 label/id 反查。
 */
@Component
public class FormImportDetailSheetWriter {

    /** 与 {@code FormImportColumnCollector.MAX_DICT_OPTIONS} 同口径：几百项的下拉没法用。 */
    private static final int MAX_LIST_OPTIONS = 20;
    private static final int MAX_LIST_CHARS = 240;

    void write(SXSSFWorkbook workbook, List<FormImportDetailSpec.Spec> specs,
               CellStyle header, CellStyle fieldIdStyle) {
        for (FormImportDetailSpec.Spec spec : specs) {
            SXSSFSheet sheet = workbook.createSheet(spec.sheetName());
            Row labels = sheet.createRow(0);
            Row ids = sheet.createRow(1);
            writeCell(labels, 0, FormImportDetailSpec.ROW_HEADER, header);
            writeCell(ids, 0, FormImportDetailSpec.ROW_ID, fieldIdStyle);
            List<FormImportDetailSpec.Column> columns = spec.columns();
            for (int index = 0; index < columns.size(); index++) {
                FormImportDetailSpec.Column column = columns.get(index);
                writeCell(labels, index + 1, column.header(), header);
                writeCell(ids, index + 1, column.id(), fieldIdStyle);
                sheet.setColumnWidth(index + 1,
                    Math.min(40, Math.max(12, displayWidth(column.header()) + 4)) * 256);
            }
            sheet.setColumnWidth(0, 10 * 256);
            // 第 2 行是给程序看的 id，藏起来但仍可编辑（与主 sheet 同一口径）。
            ids.setZeroHeight(true);
            sheet.createFreezePane(0, 1);
            addValidations(sheet, spec);
        }
    }

    /** 只对"能穷举"的列加下拉：检查项、结果、矩阵行。自由文本列不加。 */
    private void addValidations(SXSSFSheet sheet, FormImportDetailSpec.Spec spec) {
        for (int index = 0; index < spec.columns().size(); index++) {
            FormImportDetailSpec.Column column = spec.columns().get(index);
            List<String> options = spec.dictionary(column.id());
            if (options.isEmpty() || options.size() > MAX_LIST_OPTIONS) continue;
            if (String.join("", options).length() > MAX_LIST_CHARS) continue;
            DataValidationHelper helper = sheet.getDataValidationHelper();
            DataValidationConstraint constraint =
                helper.createExplicitListConstraint(options.toArray(new String[0]));
            // 数据从第 3 行开始（0 基 2）；上限与解析器一致。
            CellRangeAddressList range = new CellRangeAddressList(2,
                com.antflow.form.options.OptionTableImporter.MAX_ROWS + 1, index + 1, index + 1);
            DataValidation validation = helper.createValidation(constraint, range);
            validation.setShowErrorBox(true);
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            validation.createErrorBox("取值不在模板选项内",
                "请从下拉列表选择；也可以直接填写配置里的名称或编号");
            sheet.addValidationData(validation);
        }
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length(); index++) {
            width += value.charAt(index) > 127 ? 2 : 1;
        }
        return width;
    }

    private static void writeCell(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellType(org.apache.poi.ss.usermodel.CellType.STRING);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }
}
