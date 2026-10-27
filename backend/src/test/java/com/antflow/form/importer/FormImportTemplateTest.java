package com.antflow.form.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 模板结构：两行表头、隐藏元数据、重名 label、容器平铺、媒体不出列。 */
class FormImportTemplateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long FORM_ID = 7L;

    private final FormDefinitionService forms = mock(FormDefinitionService.class);
    private final FormImportTemplateService service = new FormImportTemplateService(forms, JSON);

    @Test
    void templateCarriesTwoHeaderRowsAndFlattensLayoutContainers() throws Exception {
        when(forms.getById(FORM_ID)).thenReturn(form(SCHEMA));
        try (XSSFWorkbook workbook = open(service.render(FORM_ID))) {
            Sheet data = workbook.getSheet(FormImportTemplateService.SHEET_DATA);
            assertThat(data).isNotNull();

            Row labels = data.getRow(0);
            assertThat(labels.getCell(0).getStringCellValue()).isEqualTo("业务单号");
            assertThat(labels.getCell(1).getStringCellValue()).isEqualTo("备注");
            // 第二个同名字段加序号，否则人眼无法区分两列。
            assertThat(labels.getCell(2).getStringCellValue()).isEqualTo("备注(2)");
            assertThat(labels.getCell(3).getStringCellValue()).isEqualTo("类型");
            assertThat(labels.getCell(4).getStringCellValue()).isEqualTo("二级下拉");
            assertThat(labels.getCell(5).getStringCellValue()).isEqualTo("日期");
            assertThat(labels.getLastCellNum()).isEqualTo((short) 6);

            Row ids = data.getRow(1);
            assertThat(ids.getCell(0).getStringCellValue()).isEqualTo("__import_key");
            // 容器（span_layout）不出列，子字段平铺；纯展示与媒体也不出列。
            assertThat(ids.getCell(1).getStringCellValue()).isEqualTo("subject");
            assertThat(ids.getCell(2).getStringCellValue()).isEqualTo("note2");
            assertThat(ids.getCell(3).getStringCellValue()).isEqualTo("kind");
            assertThat(ids.getCell(4).getStringCellValue()).isEqualTo("flag");
            assertThat(ids.getCell(5).getStringCellValue()).isEqualTo("day");
            assertThat(ids.getZeroHeight()).as("字段 id 行必须隐藏但仍可编辑").isTrue();
        }
    }

    @Test
    void metadataIsSelfDescribingAndHidden() throws Exception {
        when(forms.getById(FORM_ID)).thenReturn(form(SCHEMA));
        byte[] bytes = service.render(FORM_ID);
        try (XSSFWorkbook workbook = open(bytes)) {
            Sheet meta = workbook.getSheet(FormImportTemplateService.SHEET_META);
            assertThat(meta).isNotNull();
            assertThat(workbook.isSheetHidden(workbook.getSheetIndex(meta))).isTrue();

            JsonNode root = JSON.readTree(meta.getRow(0).getCell(0).getStringCellValue());
            assertThat(root.path("templateVersion").asInt()).isEqualTo(1);
            assertThat(root.path("formId").asLong()).isEqualTo(FORM_ID);
            assertThat(root.path("formVersion").asInt()).isEqualTo(3);
            assertThat(root.path("schemaHash").asText())
                .isEqualTo(FormImportTemplateService.schemaHash(SCHEMA));
            assertThat(root.path("fields")).hasSize(5);

            JsonNode subject = root.path("fields").get(0);
            assertThat(subject.path("fieldId").asText()).isEqualTo("subject");
            assertThat(subject.path("required").asBoolean()).isTrue();
            assertThat(subject.path("order").asInt()).isZero();
        }
    }

    @Test
    void dictionaryAndHelpSheetsExplainHowToFill() throws Exception {
        when(forms.getById(FORM_ID)).thenReturn(form(SCHEMA));
        try (XSSFWorkbook workbook = open(service.render(FORM_ID))) {
            Sheet dict = workbook.getSheet(FormImportTemplateService.SHEET_DICT);
            assertThat(dict).isNotNull();
            assertThat(workbook.isSheetHidden(workbook.getSheetIndex(dict))).isTrue();
            // 两个下拉列各一条下拉校验；都引用隐藏的 __dict。
            assertThat(workbook.getSheet(FormImportTemplateService.SHEET_DATA)
                .getDataValidations()).hasSize(2);

            Sheet help = workbook.getSheet(FormImportTemplateService.SHEET_HELP);
            assertThat(help.getRow(0).getCell(0).getStringCellValue()).isEqualTo("字段名");
            boolean hasDateFormat = false;
            for (Row row : help) {
                if (row.getCell(3) != null
                    && row.getCell(3).getStringCellValue().contains("yyyy-MM-dd")) {
                    hasDateFormat = true;
                }
            }
            assertThat(hasDateFormat).as("填写说明必须写清日期格式").isTrue();
        }
    }

    @Test
    void requiredMediaFieldFailsFastInsteadOfProducingAnUnfillableColumn() {
        String schema = "[{\"id\":\"att\",\"type\":\"file_upload\",\"label\":\"附件\","
            + "\"props\":{\"required\":true}}]";
        when(forms.getById(FORM_ID)).thenReturn(form(schema));
        assertThatThrownBy(() -> service.render(FORM_ID))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("媒体字段");
    }

    @Test
    void tooManyFieldsFailOnDownloadInsteadOfProducingAnUnimportableSheet() {
        StringBuilder schema = new StringBuilder("[");
        for (int index = 0; index < 50; index++) {
            if (index > 0) schema.append(',');
            schema.append("{\"id\":\"f").append(index).append("\",\"type\":\"text\",\"label\":\"字段")
                .append(index).append("\"}");
        }
        schema.append(']');
        when(forms.getById(FORM_ID)).thenReturn(form(schema.toString()));
        // 50 个字段 + 「业务单号」= 51 列，超过解析器的 50 列上限。
        assertThatThrownBy(() -> service.render(FORM_ID))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("50");
    }

    // ---------------------------------------------------------------- 夹具

    /** 覆盖：容器平铺、重名 label、静态选项、switch、必填、媒体/展示不出列、日期说明。 */
    private static final String SCHEMA = """
        [
          {"id":"layout","type":"span_layout","label":"布局","children":[
            {"id":"subject","type":"text","label":"备注","props":{"required":true}},
            {"id":"note2","type":"text","label":"备注"}
          ]},
          {"id":"kind","type":"select","label":"类型","props":{"options":[
            {"label":"差旅","value":"travel"},{"label":"办公","value":"office"}]}},
          {"id":"flag","type":"select","label":"二级下拉","props":{"options":[{"value":"y","label":"是"},{"value":"n","label":"否"}]}},
          {"id":"day","type":"date","label":"日期"},
          {"id":"att","type":"file_upload","label":"附件"},
          {"id":"hint","type":"description","label":"说明"}
        ]
        """;

    private static FormDefinition form(String schema) {
        FormDefinition form = new FormDefinition();
        form.setId(FORM_ID);
        form.setCode("expense");
        form.setName("报销单");
        form.setVersion(3);
        form.setStatus("PUBLISHED");
        form.setSchema(schema);
        return form;
    }

    private static XSSFWorkbook open(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }
}
