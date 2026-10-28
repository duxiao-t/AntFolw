package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.importer.ImportDtos.ColumnReport;
import com.antflow.form.importer.ImportDtos.RowIssue;
import com.antflow.form.options.OptionTableImporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 解析 + 校验阶段：xlsx → {@link Plan}（每行的解码结果、行级 issue、是否重复）。
 *
 * <p>这一步**只读不写**：所有"这行能不能导"的判断都在这里结束，写库是
 * {@link FormDataImportWriter} 的事。分开的理由很直接——预检（inspect）只要这一半，
 * 混在一起就会让"只读"的路径里躺着 INSERT。
 *
 * <p>三个容易改错的地方：
 * <ol>
 *   <li><b>去重查库只查文件里出现过的键</b>：整张表单的 import_key 可能有几十万条，
 *       全读进内存只为判重是自杀。</li>
 *   <li><b>行号用真实 Excel 行号</b>：空行被解析器丢掉后下标会漂移，
 *       用户按第 4 行去找却看到第 3 行的错误。</li>
 *   <li><b>{@code skipped} 与 {@code issues} 互斥</b>：重复不是错误，混在一起会让
 *       errorCount 把"已经导过"算成"填错了"。</li>
 * </ol>
 */
@Component
public class FormImportParser {

    /**
     * 明细上限：超过就截断并置 {@code issuesTruncated}，避免一个坏文件回几万条 issue。
     *
     * <p>公开是给 {@link FormDataImportService} 复用同一个上限做结果截断——
     * 两处各写一个 2000 迟早会不一致。
     */
    public static final int MAX_ISSUES = 2_000;

    /** 预检的预览行数。前端只画前几行，多了既慢又没意义。 */
    private static final int PREVIEW_ROWS = 20;

    private final FormDefinitionService formDefinitionService;
    private final FormImportValueCodec codec;
    private final FormImportTemplateReader templates;
    private final FormImportKeyLookup keyLookup;
    private final ObjectMapper json;
    private final FormImportDetailSheets detailSheets;

    public FormImportParser(FormDefinitionService formDefinitionService, FormImportValueCodec codec,
                            FormImportTemplateReader templates, FormImportKeyLookup keyLookup,
                            ObjectMapper json, FormImportDetailSheets detailSheets) {
        this.formDefinitionService = formDefinitionService;
        this.codec = codec;
        this.templates = templates;
        this.keyLookup = keyLookup;
        this.json = json;
        this.detailSheets = detailSheets;
    }

    Plan parse(long formId, MultipartFile file, String sheetName) {
        FormDefinition form = templates.requirePublished(formId);
        byte[] bytes = templates.readFile(file);
        JsonNode schema = templates.readSchema(form);
        FormImportTemplateReader.TemplateMeta template = templates.readMeta(bytes);
        templates.validateMeta(template.meta(), form);
        String requested = sheetName == null ? "" : sheetName.trim();
        String sheet = requested.isEmpty() ? FormImportTemplateService.SHEET_DATA : requested;
        if (!template.sheets().contains(sheet)) {
            throw new BizException("IMPORT_SHEET_REQUIRED",
                "工作表「" + sheet + "」不存在，可选：" + String.join("、", template.sheets()));
        }
        OptionTableImporter.ImportedTable table = templates.readTable(file, sheet);
        // 解析器把"表头之后的行"都给了我们；第 1 条是字段 id 行，第 2 条起才是数据。
        if (table.rows().isEmpty()) throw new BizException("IMPORT_EMPTY", "模板缺少字段标识行");

        List<String> fieldIds = table.rows().get(0);
        List<Column> columns = reconcileColumns(table.columns(), fieldIds, schema,
            template.meta().path("fields"));
        List<String> missingColumns = missingColumns(schema, columns);

        List<Map<String, String>> rawRows = new ArrayList<>();
        List<Integer> sourceRows = new ArrayList<>();
        for (int index = 1; index < table.rows().size(); index++) {
            List<String> values = table.rows().get(index);
            Map<String, String> raw = new LinkedHashMap<>();
            for (Column column : columns) {
                raw.put(column.fieldId(), column.order() < values.size() ? values.get(column.order()) : "");
            }
            rawRows.add(raw);
            sourceRows.add(table.sourceRows().get(index));
        }
        if (rawRows.isEmpty()) throw new BizException("IMPORT_EMPTY", "模板里没有数据行");
        if (rawRows.size() > OptionTableImporter.MAX_ROWS) {
            throw new BizException("IMPORT_ROW_LIMIT", "数据行不能超过 " + OptionTableImporter.MAX_ROWS + " 行");
        }

        List<String> warnings = new ArrayList<>();
        // 明细表按「行号」关联到上面这些真实行；行号非法会当场抛出（不是"尽力而为"地跳过）。
        FormImportDetailSheets.Parsed details = detailSheets.parse(schema, template.meta(), bytes,
            new LinkedHashSet<>(sourceRows));
        warnings.addAll(details.warnings());
        List<Map<String, String>> prefetchRows = new ArrayList<>(rawRows);
        prefetchRows.addAll(details.prefetchRows());
        FormImportValueCodec.Prefetch prefetch = codec.prefetch(schema, prefetchRows);
        Set<String> fileKeys = keyLookup.importKeys(rawRows);
        Set<String> existingKeys = keyLookup.existingImportKeys(form.getId(), fileKeys);
        Set<String> existingBusinessNos = keyLookup.existingBusinessNos(fileKeys);

        List<RowPlan> rows = new ArrayList<>(rawRows.size());
        Set<String> seenKeys = new LinkedHashSet<>();
        Set<String> seenHashes = new LinkedHashSet<>();
        boolean hasKeyColumn = columns.stream()
            .anyMatch(column -> FormImportTemplateService.KEY_COLUMN_ID.equals(column.fieldId()));

        for (int index = 0; index < rawRows.size(); index++) {
            Map<String, String> raw = rawRows.get(index);
            int sourceRow = sourceRows.get(index);
            List<RowIssue> issues = new ArrayList<>();
            Map<String, Object> values = decodeRow(columns, raw, sourceRow, prefetch,
                details, warnings, issues);

            boolean skipped = false;
            String importKey = null;
            String hash = null;
            if (issues.isEmpty()) {
                String rawKey = raw.get(FormImportTemplateService.KEY_COLUMN_ID);
                importKey = rawKey == null || rawKey.isBlank() ? null : rawKey.trim();
                if (importKey != null && importKey.length() > 128) {
                    issues.add(issue(sourceRow, FormImportTemplateService.KEY_COLUMN_ID,
                        FormImportTemplateService.KEY_COLUMN_LABEL, "IMPORT_VALUE_INVALID",
                        "业务单号不能超过 128 个字符"));
                } else {
                    try {
                        values = formDefinitionService.filterVisibleSubmission(form.getSchema(), values);
                        formDefinitionService.validateSubmission(form.getSchema(), values);
                    } catch (BizException error) {
                        issues.add(issue(sourceRow, locateField(error.getMessage(), columns),
                            locateLabel(error.getMessage(), columns), "IMPORT_VALIDATION_FAILED",
                            error.getMessage()));
                    }
                }
            }
            if (issues.isEmpty()) {
                if (importKey != null) {
                    // 三种重复来源：库里已有 import_key、某个已有 business_no 与之相同、文件内前面出现过。
                    skipped = existingKeys.contains(importKey)
                        || existingBusinessNos.contains(importKey)
                        || !seenKeys.add(importKey);
                } else {
                    hash = sha256(FormImportSupport.writeJson(json, values));
                    skipped = !seenHashes.add(hash);
                }
            }
            rows.add(new RowPlan(sourceRow, values, importKey, hash, List.copyOf(issues), skipped));
        }

        int errorCount = (int) rows.stream().filter(row -> !row.issues().isEmpty()).count();
        int skippedCount = (int) rows.stream().filter(RowPlan::skipped).count();
        List<RowIssue> issues = rows.stream().flatMap(row -> row.issues().stream()).toList();
        List<Map<String, Object>> previews = rows.stream()
            .filter(row -> row.issues().isEmpty() && !row.skipped())
            .limit(PREVIEW_ROWS).map(RowPlan::values).toList();
        if (!hasKeyColumn) {
            warnings.add("文件没有「业务单号」列，重复数据只在本次文件内按内容去重，跨批次无法识别");
        }
        if (skippedCount > 0) {
            warnings.add(skippedCount + " 行因业务单号或内容重复被跳过");
        }

        return new Plan(form, template.sheets(), sheet, columns, missingColumns, rows, warnings,
            rows.size(), errorCount, skippedCount, rows.size() - errorCount - skippedCount,
            issues.size() > MAX_ISSUES ? issues.subList(0, MAX_ISSUES) : issues,
            issues.size() > MAX_ISSUES, previews);
    }

    /** 一行 → 存储值。**主表单元格有值就用它，否则才看明细表**：老模板里的 JSON 不能被新格式改味，
     *  明细只是兜底；两者都有值时丢弃明细并 warning，避免"改了明细表却没生效"的困惑。 */
    private Map<String, Object> decodeRow(List<Column> columns,
                                          Map<String, String> raw, int sourceRow,
                                          FormImportValueCodec.Prefetch prefetch,
                                          FormImportDetailSheets.Parsed details,
                                          List<String> warnings, List<RowIssue> issues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Column column : columns) {
            if (FormImportTemplateService.KEY_COLUMN_ID.equals(column.fieldId())) continue;
            String text = raw.get(column.fieldId());
            boolean blank = text == null || text.isBlank();
            if (!column.supported()) {
                if (blank) continue;
                issues.add(issue(sourceRow, column.fieldId(), column.label(), "IMPORT_FIELD_UNSUPPORTED",
                    "字段「" + column.label() + "」不支持通过 Excel 导入"));
                continue;
            }
            boolean fromDetail = blank && details.has(column.fieldId(), sourceRow);
            if (!blank && details.has(column.fieldId(), sourceRow)) {
                warnings.add("第 " + sourceRow + " 行「" + column.label() + "」已按单元格内容读取，"
                    + "明细表「" + details.sheet(column.fieldId()) + "」里的对应内容被忽略");
            }
            if (blank && !fromDetail) continue;
            try {
                Object value = fromDetail
                    ? details.decode(column.fieldId(), sourceRow, prefetch)
                    : codec.decode(column.node(), text, prefetch);
                if (value != null) values.put(column.fieldId(), value);
            } catch (BizException error) {
                issues.add(issue(sourceRow, column.fieldId(), column.label(), error.getCode(),
                    error.getMessage()));
            }
        }
        return values;
    }

    private List<Column> reconcileColumns(List<String> labels, List<String> fieldIds, JsonNode schema,
                                          JsonNode metaFields) {
        if (labels.size() > OptionTableImporter.MAX_COLUMNS) {
            throw new BizException("IMPORT_TEMPLATE_INVALID",
                "列数不能超过 " + OptionTableImporter.MAX_COLUMNS);
        }
        Map<String, JsonNode> schemaFields = fieldsById(schema);
        Set<String> metaIds = new LinkedHashSet<>();
        for (JsonNode item : metaFields) metaIds.add(item.path("fieldId").asText(""));
        Set<String> seen = new LinkedHashSet<>();
        List<Column> columns = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            String fieldId = index < fieldIds.size() ? fieldIds.get(index).trim() : "";
            if (fieldId.isBlank()) {
                throw new BizException("IMPORT_TEMPLATE_INVALID",
                    "第 " + (index + 1) + " 列缺少字段标识，请重新下载模板");
            }
            if (!seen.add(fieldId)) {
                throw new BizException("IMPORT_TEMPLATE_INVALID", "字段「" + fieldId + "」在模板里出现了多次");
            }
            if (FormImportTemplateService.KEY_COLUMN_ID.equals(fieldId)) {
                columns.add(new Column(null, fieldId, FormImportTemplateService.KEY_COLUMN_LABEL,
                    "text", index, false, true));
                continue;
            }
            JsonNode node = schemaFields.get(fieldId);
            if (node == null) {
                throw new BizException("IMPORT_FIELD_UNKNOWN",
                    "模板包含表单里不存在的字段「" + fieldId + "」，请重新下载模板");
            }
            if (!metaIds.contains(fieldId)) {
                throw new BizException("IMPORT_TEMPLATE_INVALID",
                    "字段「" + fieldId + "」不在模板元数据里，文件可能被修改过");
            }
            String type = node.path("type").asText("");
            boolean isContainer = FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type);
            boolean supported = !isContainer && !"description".equals(type)
                && !FormImportTemplateService.isMedia(type);
            String label = index < labels.size() && !labels.get(index).isBlank()
                ? labels.get(index) : node.path("label").asText(fieldId);
            // 结构化字段的表头是「字段名（见「xx」表）」：报错时用户认得的是字段名，不是这个指针。
            columns.add(new Column(node, fieldId, stripDetailSuffix(label), type, index,
                required(node), supported));
        }
        return columns;
    }

    private static String stripDetailSuffix(String label) {
        int marker = label.indexOf("（见「");
        return marker > 0 ? label.substring(0, marker) : label;
    }

    private List<String> missingColumns(JsonNode schema, List<Column> columns) {
        Set<String> present = columns.stream().map(Column::fieldId).collect(java.util.stream.Collectors.toSet());
        List<String> missing = new ArrayList<>();
        collectMissing(schema, present, missing);
        return missing;
    }

    private void collectMissing(JsonNode nodes, Set<String> present, List<String> missing) {
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
                collectMissing(node.path("children"), present, missing);
                continue;
            }
            if ("description".equals(type) || FormImportTemplateService.isMedia(type)) continue;
            String id = node.path("id").asText("");
            if (!id.isBlank() && !present.contains(id)) {
                missing.add(node.path("label").asText(id));
            }
        }
    }

    private static boolean required(JsonNode node) {
        return node.path("rules").path("required")
            .asBoolean(node.path("props").path("required").asBoolean(false));
    }

    private Map<String, JsonNode> fieldsById(JsonNode schema) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        collectFieldsById(schema, fields);
        return fields;
    }

    private void collectFieldsById(JsonNode nodes, Map<String, JsonNode> fields) {
        for (JsonNode node : nodes) {
            String id = node.path("id").asText("");
            if (!id.isBlank()) fields.putIfAbsent(id, node);
            collectFieldsById(node.path("children"), fields);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }


    /** 异常消息里没有 fieldId（校验器只吐 label）：按"消息以哪个 label 开头"反查，取最长匹配。 */
    private static String locateField(String message, List<Column> columns) {
        if (message == null) return null;
        String best = null;
        int bestLength = -1;
        for (Column column : columns) {
            String label = column.label();
            if (label != null && !label.isBlank() && message.startsWith(label)
                && label.length() > bestLength) {
                best = column.fieldId();
                bestLength = label.length();
            }
        }
        return best;
    }

    private static String locateLabel(String message, List<Column> columns) {
        String fieldId = locateField(message, columns);
        for (Column column : columns) {
            if (column.fieldId().equals(fieldId)) return column.label();
        }
        return null;
    }

    private static RowIssue issue(int row, String fieldId, String label, String code, String message) {
        return new RowIssue(row, fieldId, label, code, message);
    }

    /** 一列：{@code node} 为 null 表示保留列（业务单号）。 */
    record Column(JsonNode node, String fieldId, String label, String type, int order,
                  boolean required, boolean supported) { }

    /**
     * 一行：{@code values} 是**过滤后**的存储值（与 {@code filterVisibleSubmission} 一致）。
     * {@code skipped} 与 {@code issues} 互斥——重复不算错误。
     */
    record RowPlan(int row, Map<String, Object> values, String importKey, String hash,
                   List<RowIssue> issues, boolean skipped) { }

    record Plan(FormDefinition form, List<String> sheets, String sheetName,
                List<Column> columns, List<String> missingColumns, List<RowPlan> rows,
                List<String> warnings, int rowCount, int errorCount, int skippedCount,
                int importableCount, List<RowIssue> issues, boolean issuesTruncated,
                List<Map<String, Object>> previews) {

        List<ColumnReport> columnReports() {
            return columns.stream()
                .map(column -> new ColumnReport(column.fieldId(), column.label(), column.type(),
                    column.required(), column.supported(), column.order()))
                .toList();
        }
    }
}
