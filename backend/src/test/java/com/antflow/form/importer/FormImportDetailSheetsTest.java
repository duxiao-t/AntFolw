package com.antflow.form.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.FormDefinitionServiceFixture;
import com.antflow.form.importer.FormImportValueCodec.DeptRef;
import com.antflow.form.importer.FormImportValueCodec.UserRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 明细 sheet：行号关联、label/id 混用、JSON 优先、老模板零影响。
 *
 * <p>测法刻意"半集成"：值解码走真实 {@link FormImportValueCodec}，工作簿是手工搭的
 * （模拟用户填完的文件），解析走真实 {@link FormImportParser}。这样断言的是
 * "用户填出来的东西能不能变成库里的那份数据"，而不是"我 mock 的中间结果长什么样"。
 */
class FormImportDetailSheetsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long FORM_ID = 11L;
    private static final String XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final FormDefinitionMapper mapper = mock(FormDefinitionMapper.class);
    private final FormDefinitionService forms;
    private final FormImportTemplateService templates;
    private final FormImportValueCodec codec = new FormImportValueCodec(JSON, DIRECTORY);
    private final FormImportDetailSheets detailSheets = new FormImportDetailSheets(codec, JSON);

    FormImportDetailSheetsTest() {
        FormDefinition form = form(SCHEMA);
        when(mapper.selectById(FORM_ID)).thenReturn(form);
        forms = FormDefinitionServiceFixture.service(mapper, JSON);
        templates = new FormImportTemplateService(forms, JSON);
    }

    // ---------------------------------------------------------------- 单元：组装

    @Test
    void checklistAcceptsLabelsAndIdsForBothItemAndResult() throws Exception {
        JsonNode schema = JSON.readTree(SCHEMA);
        FormImportDetailSheets.Parsed parsed = detailSheets.parse(schema, meta(), workbook(Map.of(
            "检查项", new String[][] {
                {"行号", "检查项", "结果", "描述"},
                {"__row", "__item", "__status", "__description"},
                {"3", "检查项1", "通过", "外观正常"},
                {"3", "item-2", "fail", "有划痕"}})), Set.of(3));

        assertThat(parsed.decode("chk", 3, FormImportValueCodec.Prefetch.empty()))
            .isEqualTo(List.of(
                Map.of("id", "item-1", "status", "pass", "images", List.of(),
                    "description", "外观正常"),
                Map.of("id", "item-2", "status", "fail", "images", List.of(),
                    "description", "有划痕")));
    }

    @Test
    void blankRowNumberInheritsThePreviousRow() throws Exception {
        FormImportDetailSheets.Parsed parsed = detailSheets.parse(JSON.readTree(SCHEMA), meta(),
            workbook(Map.of("检查项", new String[][] {
                {"行号", "检查项", "结果", "描述"},
                {"__row", "__item", "__status", "__description"},
                {"4", "检查项1", "通过", ""},
                {"", "检查项2", "不适用", ""}})), Set.of(3, 4));

        assertThat(parsed.has("chk", 3)).isFalse();
        assertThat(parsed.decode("chk", 4, FormImportValueCodec.Prefetch.empty()))
            .as("行号留空承接上一行")
            .isEqualTo(List.of(
                Map.of("id", "item-1", "status", "pass", "images", List.of()),
                Map.of("id", "item-2", "status", "na", "images", List.of())));
    }

    @Test
    void rowNumberThatDoesNotExistIsRejectedWithSheetAndRow() throws Exception {
        byte[] bytes = workbook(Map.of("检查项", new String[][] {
            {"行号", "检查项", "结果", "描述"},
            {"__row", "__item", "__status", "__description"},
            {"99", "检查项1", "通过", ""}}));

        assertThatThrownBy(() -> detailSheets.parse(JSON.readTree(SCHEMA), meta(), bytes, Set.of(3)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("「检查项」")
            .hasMessageContaining("第 3 行")
            .hasMessageContaining("99");
    }

    @Test
    void checklistExplainsWhichResultValuesAreAllowed() throws Exception {
        byte[] bytes = workbook(Map.of("检查项", new String[][] {
            {"行号", "检查项", "结果", "描述"},
            {"__row", "__item", "__status", "__description"},
            {"3", "检查项1", "差不多", ""}}));
        FormImportDetailSheets.Parsed parsed =
            detailSheets.parse(JSON.readTree(SCHEMA), meta(), bytes, Set.of(3));

        assertThatThrownBy(() -> parsed.decode("chk", 3, FormImportValueCodec.Prefetch.empty()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("「结果」只能填：通过、不通过、不适用")
            .hasMessageContaining("差不多");
    }

    @Test
    void tableListAndMatrixProduceExactlyTheJsonDecodedStructure() throws Exception {
        JsonNode schema = JSON.readTree(SCHEMA);
        FormImportDetailSheets.Parsed parsed = detailSheets.parse(schema, meta(), workbook(Map.of(
            "明细", new String[][] {
                {"行号", "名称", "数量"},
                {"__row", "c1", "c2"},
                {"3", "合同", "2.5"}},
            "检查矩阵", new String[][] {
                {"行号", "行", "上午", "下午"},
                {"__row", "__row_key", "c1", "c2"},
                {"3", "第一行", "1.5", "2"}})), Set.of(3));

        JsonNode tableField = field(schema, "lines");
        JsonNode matrixField = field(schema, "grid");
        assertThat(parsed.decode("lines", 3, FormImportValueCodec.Prefetch.empty()))
            .as("明细表与 JSON 写法必须落出同一份数据")
            .isEqualTo(codec.decode(tableField, "[{\"c1\":\"合同\",\"c2\":\"2.5\"}]",
                FormImportValueCodec.Prefetch.empty()));
        assertThat(parsed.decode("grid", 3, FormImportValueCodec.Prefetch.empty()))
            .isEqualTo(codec.decode(matrixField, "{\"cells\":{\"r1\":{\"c1\":\"1.5\",\"c2\":\"2\"}}}",
                FormImportValueCodec.Prefetch.empty()));
        assertThat(parsed.decode("grid", 3, FormImportValueCodec.Prefetch.empty()).toString())
            .contains("1.5");
    }

    @Test
    void oldTemplateWithoutDetailSheetsIsCompletelyIgnored() throws Exception {
        JsonNode oldMeta = JSON.readTree("{\"templateVersion\":1,\"formId\":11,\"fields\":[]}");
        FormImportDetailSheets.Parsed parsed = detailSheets.parse(JSON.readTree(SCHEMA), oldMeta,
            workbook(oldMeta, Map.of()), Set.of(3));

        assertThat(parsed.has("chk", 3)).isFalse();
        assertThat(parsed.prefetchRows()).isEmpty();
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void declaredButMissingDetailSheetOnlyWarns() throws Exception {
        JsonNode onlyChecklist = JSON.readTree("""
            {"templateVersion":1,"formId":11,"fields":[],"detailSheets":[
              {"sheet":"检查项","fieldId":"chk","kind":"checklist"}]}
            """);
        FormImportDetailSheets.Parsed parsed = detailSheets.parse(JSON.readTree(SCHEMA),
            onlyChecklist, workbook(onlyChecklist, Map.of()), Set.of(3));

        assertThat(parsed.has("chk", 3)).isFalse();
        assertThat(parsed.warnings()).singleElement()
            .satisfies(warning -> assertThat(warning).contains("检查项").contains("不存在"));
    }

    // ---------------------------------------------------------------- 端到端：优先级

    @Test
    void mainSheetJsonWinsOverDetailSheetAndSaysSoInWarnings() throws Exception {
        FormImportParser.Plan plan = parser().parse(FORM_ID, file(
            new String[] {"K1", "出差", "[{\"id\":\"item-9\",\"status\":\"pass\"}]", "", ""},
            Map.of("检查项", new String[][] {
                {"行号", "检查项", "结果", "描述"},
                {"__row", "__item", "__status", "__description"},
                {"3", "检查项1", "不通过", ""}})), null, null, true);

        assertThat(plan.errorCount()).as(plan.issues().toString()).isZero();
        assertThat(plan.rows().get(0).values().get("chk"))
            .isEqualTo(List.of(Map.of("id", "item-9", "status", "pass", "images", List.of())));
        assertThat(plan.warnings()).anyMatch(warning -> warning.contains("明细表")
            && warning.contains("被忽略"));
    }

    @Test
    void detailSheetIsUsedWhenTheMainCellIsEmpty() throws Exception {
        FormImportParser.Plan plan = parser().parse(FORM_ID, file(
            new String[] {"K1", "出差", "", "", ""},
            Map.of(
                "检查项", new String[][] {
                    {"行号", "检查项", "结果", "描述"},
                    {"__row", "__item", "__status", "__description"},
                    {"3", "检查项1", "通过", ""}},
                "明细", new String[][] {
                    {"行号", "名称", "数量"},
                    {"__row", "c1", "c2"},
                    {"3", "合同", "2"},
                    {"3", "补充", "1"}},
                "检查矩阵", new String[][] {
                    {"行号", "行", "上午", "下午"},
                    {"__row", "__row_key", "c1", "c2"},
                    {"3", "第二行", "", "7"}})), null, null, true);

        assertThat(plan.errorCount()).isZero();
        Map<String, Object> values = plan.rows().get(0).values();
        assertThat(values.get("chk")).isEqualTo(List.of(
            Map.of("id", "item-1", "status", "pass", "images", List.of())));
        assertThat(values.get("lines")).isEqualTo(List.of(
            Map.of("c1", "合同", "c2", new BigDecimal("2")),
            Map.of("c1", "补充", "c2", new BigDecimal("1"))));
        assertThat(values.get("grid")).isEqualTo(Map.of(
            "cells", Map.of("r2", Map.of("c2", new BigDecimal("7")))));
    }

    @Test
    void templateGivesEachStructuredFieldItsOwnSheetAndAPointerColumn() throws Exception {
        byte[] bytes = templates.render(FORM_ID);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet("检查项")).isNotNull();
            assertThat(workbook.getSheet("明细")).isNotNull();
            assertThat(workbook.getSheet("检查矩阵")).isNotNull();

            Sheet data = workbook.getSheet(FormImportTemplateService.SHEET_DATA);
            // 第 0 列是「业务单号」，第 1 列「事由」，第 2 列才是「检查项」。
            String header = data.getRow(0).getCell(2).getStringCellValue();
            assertThat(header).isEqualTo("检查项（见「检查项」表）");
            // 第二行的 fieldId 保持不变：老模板/老脚本仍然认得这一列。
            assertThat(data.getRow(1).getCell(2).getStringCellValue()).isEqualTo("chk");

            Sheet detail = workbook.getSheet("检查项");
            assertThat(detail.getRow(0).getCell(0).getStringCellValue()).isEqualTo("行号");
            assertThat(detail.getRow(1).getCell(1).getStringCellValue()).isEqualTo("__item");
            assertThat(detail.getRow(1).getZeroHeight()).isTrue();
            // 结果列有下拉（3 个候选）。
            assertThat(detail.getDataValidations()).isNotEmpty();

            JsonNode meta = JSON.readTree(workbook
                .getSheet(FormImportTemplateService.SHEET_META).getRow(0).getCell(0)
                .getStringCellValue());
            assertThat(meta.path("detailSheets")).hasSize(3);
            assertThat(meta.path("templateVersion").asInt()).as("不升版本，老模板继续可用").isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- 夹具

    private FormImportParser parser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
            .thenReturn(List.of());
        return new FormImportParser(forms, codec, new FormImportTemplateReader(forms, JSON),
            new FormImportKeyLookup(jdbc), JSON, detailSheets);
    }

    /** 主表一行数据 + 若干明细表（第 3 行起）。 */
    private MultipartFile file(String[] mainRow, Map<String, String[][]> details) throws Exception {
        byte[] base = templates.render(FORM_ID);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(base));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = workbook.getSheet(FormImportTemplateService.SHEET_DATA);
            Row row = data.getRow(2);
            if (row == null) row = data.createRow(2);
            for (int column = 0; column < mainRow.length; column++) {
                row.createCell(column).setCellValue(mainRow[column]);
            }
            details.forEach((name, grid) -> fill(workbook.getSheet(name), grid));
            workbook.write(out);
            return new MockMultipartFile("file", "import.xlsx", XLSX, out.toByteArray());
        }
    }

    private JsonNode meta() throws Exception {
        return JSON.readTree("""
            {"templateVersion":1,"formId":11,"fields":[],"detailSheets":[
              {"sheet":"检查项","fieldId":"chk","kind":"checklist"},
              {"sheet":"明细","fieldId":"lines","kind":"table_list"},
              {"sheet":"检查矩阵","fieldId":"grid","kind":"matrix_fill"}]}
            """);
    }

    private byte[] workbook(Map<String, String[][]> detailSheets) throws Exception {
        return workbook(meta(), detailSheets);
    }

    private byte[] workbook(JsonNode meta, Map<String, String[][]> detailSheets) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("x");
            workbook.createSheet(FormImportTemplateService.SHEET_META).createRow(0)
                .createCell(0).setCellValue(meta.toString());
            detailSheets.forEach((name, grid) -> fill(workbook.createSheet(name), grid));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void fill(Sheet sheet, String[][] grid) {
        for (int rowIndex = 0; rowIndex < grid.length; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);
            for (int column = 0; column < grid[rowIndex].length; column++) {
                row.createCell(column).setCellValue(grid[rowIndex][column]);
            }
        }
    }

    private static JsonNode field(JsonNode schema, String id) {
        for (JsonNode node : schema) {
            if (id.equals(node.path("id").asText())) return node;
        }
        throw new IllegalStateException("field not found: " + id);
    }

    private static FormDefinition form(String schema) {
        FormDefinition form = new FormDefinition();
        form.setId(FORM_ID);
        form.setCode("inspect");
        form.setName("点检单");
        form.setVersion(1);
        form.setStatus("PUBLISHED");
        form.setSchema(schema);
        return form;
    }

    private static final String SCHEMA = """
        [
          {"id":"subject","type":"text","label":"事由","props":{"required":true}},
          {"id":"chk","type":"checklist","label":"检查项","props":{
            "items":[{"id":"item-1","label":"检查项1"},{"id":"item-2","label":"检查项2"}],
            "results":[{"id":"pass","label":"通过"},{"id":"fail","label":"不通过"},
                       {"id":"na","label":"不适用"}],
            "descriptionRequiredByResult":{"fail":true}}},
          {"id":"lines","type":"table_list","label":"明细","children":[
            {"id":"c1","type":"text","label":"名称"},
            {"id":"c2","type":"number","label":"数量"}]},
          {"id":"grid","type":"matrix_fill","label":"检查矩阵","props":{
            "cellType":"number","precision":2,"maxRows":10,"maxColumns":10,
            "rows":[{"id":"r1","label":"第一行"},{"id":"r2","label":"第二行"}],
            "columns":[{"id":"c1","label":"上午"},{"id":"c2","label":"下午"}]}}
        ]
        """;

    private static final FormImportValueCodec.Directory DIRECTORY =
        new FormImportValueCodec.Directory() {
            @Override
            public List<UserRef> users(Collection<String> tokens) {
                return List.of(new UserRef(11, "E001", "李四"));
            }

            @Override
            public List<DeptRef> departments() {
                return List.of(new DeptRef(1, "总部", null));
            }

            @Override
            public List<Map<String, String>> optionRows(long versionId) {
                return List.of();
            }
        };

    /**
     * 用户把明细表的第 2 行（标识行）删掉、数据上移：必须明确报"第 2 行"。
     *
     * <p>这是真实会发生的操作（看着表头碍眼就删一行）。旧判据只检查"整行没有任何标识"，
     * 删行后那一行仍是有内容的（数据），会被当成标识行 → 所有列都对不上 → 明细静默全空，
     * 最后只在必填校验处冒出一句「请完成检查项」，用户完全想不到是表头被删了。
     */
    @Test
    void deletingTheIdRowIsRejectedInsteadOfSilentlyEmpty() throws Exception {
        byte[] bytes = workbook(Map.of("检查项", new String[][]{
            {"行号", "检查项", "结果", "描述"},
            {"3", "检查项1", "通过", ""}}));

        assertThatThrownBy(() -> detailSheets.parse(JSON.readTree(SCHEMA), meta(), bytes, Set.of(3)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("第 2 行");
    }
}
