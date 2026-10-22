package com.antflow.form.options;

import com.antflow.engine.BizException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OptionTableImporter} 的特征测试。
 *
 * <p>这个簇从 {@code OptionSourceService} 搬出来时**原本没有任何测试**——解析逻辑只被集成测试间接覆盖，
 * 一旦改坏，表现是"用户导入的表少了一列"而不是测试变红。所以这里按主题把当前行为固定下来：
 * 断言的是**今天的行为**（包括"空行被丢弃""缺列补空串"这类产品没写进文档的约定），不是理想行为。
 *
 * <p>不启动 Spring：解析是纯函数，用 {@link MockMultipartFile} 造输入即可，几毫秒跑完。
 */
class OptionTableImporterTest {

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static String codeOf(Throwable error) {
        return ((BizException) error).getCode();
    }

    @Nested
    class CsvParsing {

        @Test
        void parsesHeaderAndRows() throws Exception {
            OptionTableImporter.ImportedTable table =
                OptionTableImporter.read(csv("a.csv", "部门,负责人\n研发,张三\n销售,李四\n"), null, null);

            assertThat(table.columns()).containsExactly("部门", "负责人");
            assertThat(table.rows()).hasSize(2);
            assertThat(table.rows().get(0)).containsExactly("研发", "张三");
        }

        @Test
        void keepsDelimiterAndNewlineInsideQuotes() throws Exception {
            // 引号内的逗号与换行不能被当成分隔符——CSV 引号状态机存在的唯一理由。
            OptionTableImporter.ImportedTable table =
                OptionTableImporter.read(csv("a.csv", "名称,说明\n\"甲,乙\",\"第一行\n第二行\"\n"), null, null);

            assertThat(table.rows()).hasSize(1);
            assertThat(table.rows().get(0)).containsExactly("甲,乙", "第一行\n第二行");
        }

        @Test
        void unescapesDoubledQuotes() throws Exception {
            OptionTableImporter.ImportedTable table =
                OptionTableImporter.read(csv("a.csv", "值\n\"他说\"\"好\"\"\"\n"), null, null);

            assertThat(table.rows().get(0)).containsExactly("他说\"好\"");
        }

        @Test
        void rejectsUnclosedQuote() {
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.csv", "值\n\"未闭合\n"), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_CSV_INVALID"));
        }

        @Test
        void dropsBlankLines() throws Exception {
            OptionTableImporter.ImportedTable table =
                OptionTableImporter.read(csv("a.csv", "值\na\n\n\nb\n"), null, null);

            assertThat(table.rows()).hasSize(2);
        }
    }

    @Nested
    class PastedText {

        @Test
        void singleColumnWhenNoTab() throws Exception {
            OptionTableImporter.ImportedTable table = OptionTableImporter.read(null, "甲\n乙\n", null);

            assertThat(table.columns()).containsExactly("选项");
            assertThat(table.rows()).containsExactly(List.of("甲"), List.of("乙"));
        }

        @Test
        void multiColumnWhenTabPresent() throws Exception {
            OptionTableImporter.ImportedTable table = OptionTableImporter.read(null, "代码\t名称\nA\t甲\n", null);

            assertThat(table.columns()).containsExactly("代码", "名称");
            assertThat(table.rows().get(0)).containsExactly("A", "甲");
        }

        @Test
        void blankTextIsTreatedAsNotProvided() {
            // 记录的是**当前行为**：全空白的 text 在 read() 的"二选一"判定里等同于没提供，
            // 报 IMPORT_REQUIRED 而不是 EMPTY。改动这条要同步改前端提示文案，不能只改这里。
            assertThatThrownBy(() -> OptionTableImporter.read(null, "   \n\n", null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_IMPORT_REQUIRED"));
        }
    }

    @Nested
    class InputSelection {

        @Test
        void rejectsWhenBothFileAndTextProvided() {
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.csv", "值\na\n"), "值\na\n", null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_IMPORT_REQUIRED"));
        }

        @Test
        void rejectsWhenNeitherProvided() {
            assertThatThrownBy(() -> OptionTableImporter.read(null, null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_IMPORT_REQUIRED"));
        }

        @Test
        void rejectsUnsupportedExtension() {
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.json", "{}"), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_TYPE_UNSUPPORTED"));
        }
    }

    @Nested
    class TableValidation {

        @Test
        void rejectsDuplicateHeader() {
            assertThatThrownBy(() -> OptionTableImporter.read(null, "名称\t名称\nA\tB\n", null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_HEADER_INVALID"));
        }

        @Test
        void rejectsBlankHeader() {
            assertThatThrownBy(() -> OptionTableImporter.read(null, "名称\t\nA\tB\n", null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_HEADER_INVALID"));
        }

        @Test
        void rejectsTooManyColumns() {
            String header = String.join(",", java.util.Collections.nCopies(51, "c"));
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.csv", header + "\n" + "x,".repeat(50) + "x\n"), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_COLUMNS_INVALID"));
        }

        @Test
        void rejectsHeaderOnlyWithoutRows() {
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.csv", "名称\n"), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_ROWS_INVALID"));
        }

        @Test
        void rejectsRowWiderThanHeader() {
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.csv", "名称\nA,B\n"), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_ROW_INVALID"));
        }

        @Test
        void rejectsOversizedCell() {
            String tooLong = "x".repeat(501);
            assertThatThrownBy(() -> OptionTableImporter.read(csv("a.csv", "名称\n" + tooLong + "\n"), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_CELL_TOO_LONG"));
        }
    }

    @Nested
    class XlsxParsing {

        private static MockMultipartFile workbook(String name, boolean secondSheet) throws Exception {
            try (XSSFWorkbook book = new XSSFWorkbook();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                var sheet = book.createSheet("选项");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("代码");
                header.createCell(1).setCellValue("名称");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("A");
                row.createCell(1).setCellValue("甲");
                if (secondSheet) book.createSheet("备用");
                book.write(out);
                return new MockMultipartFile("file", name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
            }
        }

        @Test
        void parsesSingleSheet() throws Exception {
            OptionTableImporter.ImportedTable table =
                OptionTableImporter.read(workbook("a.xlsx", false), null, null);

            assertThat(table.columns()).containsExactly("代码", "名称");
            assertThat(table.rows().get(0)).containsExactly("A", "甲");
        }

        @Test
        void requiresSheetNameWhenMultipleSheets() throws Exception {
            assertThatThrownBy(() -> OptionTableImporter.read(workbook("a.xlsx", true), null, null))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_SHEET_REQUIRED"));
        }

        @Test
        void rejectsUnknownSheet() throws Exception {
            assertThatThrownBy(() -> OptionTableImporter.read(workbook("a.xlsx", true), null, "不存在"))
                .satisfies(error -> assertThat(codeOf(error)).isEqualTo("OPTION_SOURCE_SHEET_NOT_FOUND"));
        }
    }

    @Nested
    class Helpers {

        @Test
        void safeNameStripsClientPath() {
            // 浏览器可能传 C:\fakepath\...，直接入库会泄露客户端目录结构。
            assertThat(OptionTableImporter.safeName("C:\\fakepath\\a.csv")).isEqualTo("a.csv");
            assertThat(OptionTableImporter.safeName("/tmp/a.csv")).isEqualTo("a.csv");
            assertThat(OptionTableImporter.safeName(null)).isEqualTo("import");
            assertThat(OptionTableImporter.safeName("  ")).isEqualTo("import");
        }

        @Test
        void rowMapFillsMissingColumnsWithEmptyString() {
            Map<String, String> row = OptionTableImporter.rowMap(List.of("a", "b", "c"), List.of("1"));

            assertThat(row).containsExactly(Map.entry("a", "1"), Map.entry("b", ""), Map.entry("c", ""));
        }

        @Test
        void rowMapIgnoresExtraValues() {
            // 多余的列在 validateTable 阶段就报错了，这里只保证不会越界。
            Map<String, String> row = OptionTableImporter.rowMap(List.of("a"), List.of("1", "2"));

            assertThat(row).containsExactly(Map.entry("a", "1"));
        }
    }
}
