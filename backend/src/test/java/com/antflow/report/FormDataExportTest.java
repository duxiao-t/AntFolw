package com.antflow.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.antflow.form.runtime.FormData;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 导出最容易写错的三处：CSV 转义（一个逗号就能让整行错列）、公式注入（Excel 会执行 `=1+1`）、
 * 以及列的身份（按标签当身份会让同名不同字段互相覆盖）。规则都很短，所以直接钉住。
 */
class FormDataExportTest {

    @Test
    void escapesCommasQuotesNewlinesAndEdgeSpaces() {
        assertThat(FormDataExport.cell("普通")).isEqualTo("普通");
        assertThat(FormDataExport.cell("北京市, 朝阳区")).isEqualTo("\"北京市, 朝阳区\"");
        assertThat(FormDataExport.cell("他说\"好\"")).isEqualTo("\"他说\"\"好\"\"\"");
        assertThat(FormDataExport.cell("第一行\n第二行")).isEqualTo("\"第一行\n第二行\"");
        assertThat(FormDataExport.cell(" 前导空格")).isEqualTo("\" 前导空格\"");
        assertThat(FormDataExport.cell(null)).isEmpty();
    }

    @Test
    void formulaLikeValuesAreWrittenAsTextNotFormulas() {
        // 字段值、姓名、部门名都可能以这几个字符开头；原样写出去 Excel 会执行它。
        assertThat(FormDataExport.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(FormDataExport.cell("+1")).isEqualTo("'+1");
        assertThat(FormDataExport.cell("-1")).isEqualTo("'-1");
        assertThat(FormDataExport.cell("@x")).isEqualTo("'@x");
        assertThat(FormDataExport.cell(" =1+1")).isEqualTo("' =1+1");
        // 只有开头危险，中间的等号照旧。
        assertThat(FormDataExport.cell("a=1")).isEqualTo("a=1");
    }

    @Test
    void writesBomFixedColumnsAndFieldUnionWithoutColumnShift() {
        FormData first = row(1L, "000000000001", "张三", "000003", "技术部",
            OffsetDateTime.of(2026, 9, 29, 2, 30, 0, 0, ZoneOffset.UTC),
            List.of(value("f1", "申请人", "张三"), value("f2", "备注", "带,逗号")));
        // 第二行的字段集合不同（表单改过版本）：按第一行定表头的话，这里的值会错位到别的列。
        FormData second = row(2L, "000000000002", null, null, null, null,
            List.of(value("f3", "金额", "100")));

        String csv = new String(FormDataExport.csv(
            FormDataExport.model(List.of(first, second), ZoneOffset.ofHours(8))),
            StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv.lines().findFirst().orElseThrow())
            .isEqualTo("\uFEFF业务单号,提交人,工号,部门,提交时间,状态,申请人,备注,金额");
        // 时区换算：UTC 02:30 → +08 的 10:30（不换算导出里会比用户看到的早 8 小时）
        assertThat(csv).contains("000000000001,张三,000003,技术部,2026-09-29 10:30:00,已提交,张三,\"带,逗号\",");
        // 第二行缺的列留空，金额落在它自己的列上（9 列 → 8 个逗号）
        assertThat(csv).contains("000000000002,,,,,已提交,,,100");
    }

    @Test
    void columnsAreIdentifiedByFieldIdSoSameLabelsDoNotCollide() {
        // 表单改版后两个不同字段可能叫同一个标签：按标签当身份会互相覆盖（甚至只剩一列）。
        FormData data = row(1L, "000000000001", "张三", "000003", "技术部", null, List.of(
            value("f1", "工艺", "车削"), value("f2", "工艺", "镗削")));

        FormDataExport.Model model = FormDataExport.model(List.of(data), ZoneOffset.UTC);

        assertThat(model.headers()).containsExactly("业务单号", "提交人", "工号", "部门", "提交时间", "状态",
            "工艺", "工艺");
        assertThat(model.rows().get(0)).containsExactly("000000000001", "张三", "000003", "技术部", "",
            "已提交", "车削", "镗削");
    }

    @Test
    void detailTextWithNewlinesIsFlattenedIntoSeparators() {
        FormData data = row(1L, null, null, null, null, null, List.of(
            value("c1", "检查项", "检查项1：通过（1 张图：a.png）\n检查项2：不适用")));

        String csv = new String(FormDataExport.csv(
            FormDataExport.model(List.of(data), ZoneOffset.UTC)), StandardCharsets.UTF_8);

        assertThat(csv).contains("检查项1：通过（1 张图：a.png）；检查项2：不适用");
    }

    @Test
    void xlsxKeepsEverythingAsTextWithLeadingZerosAndTruncatesOverlongCells() throws Exception {
        FormData data = row(1L, "000000000001", "张三", "000003", "技术部",
            OffsetDateTime.of(2026, 9, 29, 2, 30, 0, 0, ZoneOffset.UTC),
            List.of(value("f1", "备注", "=1+1"), value("f2", "长文本", "长".repeat(40_000))));

        byte[] body = FormDataExport.xlsx(FormDataExport.model(List.of(data), ZoneOffset.UTC));

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("业务单号");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("备注");
            Row row = sheet.getRow(1);
            // 工号的前导零必须保住（写成数字就变成 3 了）。
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("000003");
            // 危险开头在 xlsx 里也是文本（STRING 单元格本来就写不成公式，这里是钉住别退化成 numeric）。
            assertThat(row.getCell(6).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("=1+1");
            assertThat(row.getCell(7).getStringCellValue()).hasSize(32767);
        }
    }

    private static FormData.FieldValue value(String fieldId, String label, String detail) {
        return new FormData.FieldValue(fieldId, label, detail, detail, detail);
    }

    private static FormData row(Long id, String businessNo, String name, String employeeNo,
                                String deptName, OffsetDateTime createdAt,
                                List<FormData.FieldValue> values) {
        FormData data = new FormData();
        data.setId(id);
        data.setBusinessNo(businessNo);
        data.setCreatedByName(name);
        data.setCreatedByEmployeeNo(employeeNo);
        data.setCreatedByDeptName(deptName);
        data.setCreatedAt(createdAt);
        data.setStatus("SUBMITTED");
        data.setFieldValues(new ArrayList<>(values));
        return data;
    }
}
