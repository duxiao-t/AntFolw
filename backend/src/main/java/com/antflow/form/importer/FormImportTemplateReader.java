package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.options.OptionTableImporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 把上传文件变成"可信的模板内容"：文件合法性 → 元数据自描述 → 工作表数据。
 *
 * <p>单独一类的理由：这一层只和 POI 与"模板是不是系统生成的"打交道，加字段类型、改校验规则
 * 都不该动它；而它每次改动都要重新想一遍"用户能不能伪造一个模板"。
 *
 * <p>两道刻意的前置拦截：
 * <ol>
 *   <li>**元数据缺失即拒绝**：没有 {@code __antflow_meta} 就不是系统模板。宁可让用户重新下载，
 *       也不要引入"猜表头"的映射逻辑——补录数据不该靠猜。</li>
 *   <li>**schemaHash 比 formVersion 更严**：只改了标签也算结构变化，否则列顺序/字段集合
 *       可能已经对不上，导进去会串列。</li>
 * </ol>
 */
@Component
public class FormImportTemplateReader {

    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;

    public FormImportTemplateReader(FormDefinitionService formDefinitionService, ObjectMapper json) {
        this.formDefinitionService = formDefinitionService;
        this.json = json;
    }

    FormDefinition requirePublished(long formId) {
        FormDefinition form = formDefinitionService.getById(formId);
        if (form == null || !"PUBLISHED".equals(form.getStatus())) {
            throw new BizException("IMPORT_FORM_NOT_PUBLISHED", "只有已发布的表单可以导入数据");
        }
        return form;
    }

    JsonNode readSchema(FormDefinition form) {
        try {
            return json.readTree(form.getSchema() == null ? "[]" : form.getSchema());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "表单结构无法解析");
        }
    }

    byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("IMPORT_EMPTY", "请上传要导入的 xlsx 文件");
        }
        if (file.getSize() > OptionTableImporter.MAX_BYTES) {
            throw new BizException("IMPORT_FILE_TOO_LARGE",
                "导入文件不能超过 " + (OptionTableImporter.MAX_BYTES / 1024 / 1024) + " MB");
        }
        String name = OptionTableImporter.safeName(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) {
            throw new BizException("IMPORT_FORMAT_UNSUPPORTED", "只接受系统生成的 .xlsx 模板");
        }
        try {
            return file.getBytes();
        } catch (IOException error) {
            throw new BizException("IMPORT_FILE_UNREADABLE", "无法读取上传文件");
        }
    }

    /** 元数据表 + 工作表清单。都没有就是"不是系统模板"。 */
    record TemplateMeta(List<String> sheets, JsonNode meta) { }

    TemplateMeta readMeta(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> sheets = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                sheets.add(workbook.getSheetName(index));
            }
            Sheet metaSheet = workbook.getSheet(FormImportTemplateService.SHEET_META);
            if (metaSheet == null) {
                throw new BizException("IMPORT_TEMPLATE_INVALID",
                    "文件不是系统生成的导入模板（缺少 " + FormImportTemplateService.SHEET_META + "）");
            }
            Row row = metaSheet.getRow(0);
            Cell cell = row == null ? null : row.getCell(0);
            String text = cell == null ? "" : new DataFormatter().formatCellValue(cell);
            if (text.isBlank()) {
                throw new BizException("IMPORT_TEMPLATE_INVALID", "模板元数据为空，请重新下载模板");
            }
            return new TemplateMeta(sheets, json.readTree(text));
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException("IMPORT_FILE_UNREADABLE", "无法解析文件，请确认是未损坏的 xlsx 模板");
        }
    }

    void validateMeta(JsonNode meta, FormDefinition form) {
        if (meta == null || !meta.isObject()) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "模板元数据格式不正确");
        }
        if (meta.path("templateVersion").asInt(0) != FormImportTemplateService.TEMPLATE_VERSION) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "模板版本不受支持，请重新下载");
        }
        if (meta.path("formId").asLong(0) != form.getId()) {
            throw new BizException("IMPORT_TEMPLATE_MISMATCH", "模板属于另一张表单，请重新下载");
        }
        // schemaHash 比 formVersion 更精确：只改标签也要重新下载，否则列顺序/字段集合可能已经变了。
        if (meta.path("formVersion").asInt(-1) != (form.getVersion() == null ? 0 : form.getVersion())
            || !FormImportTemplateService.schemaHash(form.getSchema())
                .equals(meta.path("schemaHash").asText(""))) {
            throw new BizException("IMPORT_TEMPLATE_MISMATCH",
                "表单结构已更新，请重新下载模板后再导入");
        }
        if (!meta.path("fields").isArray()) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "模板元数据缺少字段清单");
        }
    }

    OptionTableImporter.ImportedTable readTable(MultipartFile file, String sheet) {
        try {
            return OptionTableImporter.read(file, null, sheet);
        } catch (BizException error) {
            throw mapReadError(error);
        } catch (Exception error) {
            throw new BizException("IMPORT_FILE_UNREADABLE", "无法解析工作表，请确认文件未加密且格式正确");
        }
    }

    /**
     * 解析器的错误码是"选项源"语境（OPTION_SOURCE_*），这里翻成导入语境。
     * 不翻的话前端按 code 分支只会看到一个陌生前缀。
     */
    private static BizException mapReadError(BizException error) {
        String code = switch (error.getCode() == null ? "" : error.getCode()) {
            case "OPTION_SOURCE_TOO_LARGE" -> "IMPORT_FILE_TOO_LARGE";
            case "OPTION_SOURCE_EMPTY" -> "IMPORT_EMPTY";
            case "OPTION_SOURCE_ROWS_INVALID" -> "IMPORT_ROW_LIMIT";
            case "OPTION_SOURCE_COLUMNS_INVALID", "OPTION_SOURCE_HEADER_INVALID",
                "OPTION_SOURCE_ROW_INVALID" -> "IMPORT_TEMPLATE_INVALID";
            case "OPTION_SOURCE_CELL_TOO_LONG" -> "IMPORT_VALUE_INVALID";
            case "OPTION_SOURCE_SHEET_REQUIRED", "OPTION_SOURCE_SHEET_NOT_FOUND" -> "IMPORT_SHEET_REQUIRED";
            case "OPTION_SOURCE_TYPE_UNSUPPORTED" -> "IMPORT_FORMAT_UNSUPPORTED";
            default -> "IMPORT_FILE_UNREADABLE";
        };
        return new BizException(code, error.getMessage());
    }
}
