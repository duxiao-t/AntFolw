package com.antflow.form.importer;

import com.antflow.authz.AuthorizationService;
import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.importer.FormImportParser.RowPlan;
import com.antflow.form.importer.ImportDtos.ImportOutcome;
import com.antflow.form.importer.ImportDtos.ManualImportRequest;
import com.antflow.form.importer.ImportDtos.RowIssue;
import com.antflow.org.CompanyAttribution;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 在线填表 → 直接落一条 {@code SUBMITTED} 台账（向导里"补录一条"用的端点）。
 *
 * <p>与文件导入**共用**同一套校验与写入口：{@code filterVisibleSubmission → validateSubmission →
 * business_no → INSERT}。共用而不是复制，是因为两条路径一旦各写一份"什么算能导"，
 * 就会出现"Excel 能导、在线填表导不进"这种最难解释的差异。
 *
 * <p>三条容易踩的点：
 * <ol>
 *   <li><b>{@code data} 是已经结构化的原值</b>：前端用表单渲染器采集，本来就是
 *       {@code t_form_data.data} 的形状。这里**不做任何反解析**，否则会多出一层
 *       "在线填的值和导出的值长得不一样"。</li>
 *   <li><b>校验失败也要给字段级定位</b>：校验器的消息有时只有 label、有时只说
 *       "请完成检查项"（完全没有字段名）。单行导入没有"第几行"可以兜底，所以这里把
 *       消息翻成"哪个字段、怎么改"。{@link ManualImportFailure} 携带 failures，
 *       由控制器原样放进 422 响应。</li>
 *   <li><b>幂等只在填了 importKey 时生效</b>：与文件导入一致——没填外部单号就是一条新数据，
 *       不能因为"内容一样"把用户明确提交的两次当成重复。</li>
 * </ol>
 */
@Service
public class FormDataManualImport {

    private final FormImportTemplateReader templates;
    private final FormDefinitionService formDefinitionService;
    private final FormImportKeyLookup keyLookup;
    private final FormDataImportWriter writer;
    private final CompanyAttribution companyAttribution;
    private final AuthorizationService authorizationService;

    public FormDataManualImport(FormImportTemplateReader templates,
                                FormDefinitionService formDefinitionService,
                                FormImportKeyLookup keyLookup, FormDataImportWriter writer,
                                CompanyAttribution companyAttribution,
                                AuthorizationService authorizationService) {
        this.templates = templates;
        this.formDefinitionService = formDefinitionService;
        this.keyLookup = keyLookup;
        this.writer = writer;
        this.companyAttribution = companyAttribution;
        this.authorizationService = authorizationService;
    }

    /** 提交一条。校验失败抛 {@link ManualImportFailure}（422 IMPORT_VALIDATION_FAILED）。 */
    public ImportOutcome importOne(ManualImportRequest request) {
        if (request == null || request.formId() <= 0) {
            throw new BizException("IMPORT_REQUEST_INVALID", "缺少表单 id，无法提交");
        }
        FormDefinition form = templates.requirePublished(request.formId());
        JsonNode schema = templates.readSchema(form);
        Map<String, Object> data = request.data() == null ? Map.of() : request.data();
        Long companyId = companyAttribution.resolve(request.companyId());
        String importKey = importKey(request.importKey());
        Map<String, Object> values = validate(form, schema, data);
        ensureNotDuplicate(form, companyId, importKey);

        long userId = authorizationService.currentUserId();
        String batchId = nextBatchId();
        // 行号固定 1：单条提交没有"Excel 行号"，前端只需要"这一条"的定位。
        RowPlan row = new RowPlan(1, values, importKey, null, List.of(), false);
        try {
            writer.insertAtomically(form, List.of(row), userId, companyId, batchId);
        } catch (org.springframework.dao.DuplicateKeyException error) {
            if (importKey == null) throw error;
            // 并发下的兜底：唯一索引拒绝 → 与预检同一个错误语义，不要漏成 500。
            throw new BizException("IMPORT_DUPLICATE",
                "业务单号「" + importKey + "」已经导入过，请勿重复提交");
        }
        List<Long> createdIds = writer.loadCreatedIds(batchId);
        List<String> warnings = importKey == null
            ? List.of("未填业务单号：这条数据按新增写入，跨批次无法识别重复")
            : List.of();
        return new ImportOutcome(form.getId(), batchId, true, 1, 1, 0, 0, createdIds,
            List.of(), false, warnings);
    }

    private Map<String, Object> validate(FormDefinition form, JsonNode schema,
                                         Map<String, Object> data) {
        Map<String, Object> values;
        try {
            values = formDefinitionService.filterVisibleSubmission(form.getSchema(), data);
        } catch (BizException error) {
            throw new ManualImportFailure(failures(schema, Map.of(), error.getMessage()));
        }
        try {
            formDefinitionService.validateSubmission(form.getSchema(), values);
        } catch (BizException error) {
            throw new ManualImportFailure(failures(schema, values, error.getMessage()));
        }
        return values;
    }

    private void ensureNotDuplicate(FormDefinition form, Long companyId, String importKey) {
        if (importKey == null) return;
        Set<String> keys = Set.of(importKey);
        boolean exists = !keyLookup.existingImportKeys(form.getId(), companyId, keys).isEmpty()
            || !keyLookup.existingBusinessNos(keys).isEmpty();
        if (exists) {
            throw new BizException("IMPORT_DUPLICATE",
                "业务单号「" + importKey + "」已经导入过，请勿重复提交");
        }
    }

    private static String importKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > 128) {
            throw new BizException("IMPORT_VALUE_INVALID", "「业务单号」不能超过 128 个字符");
        }
        return value;
    }

    private static String nextBatchId() {
        return "MAN-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- 字段级定位

    private static List<RowIssue> failures(JsonNode schema, Map<String, Object> values,
                                           String message) {
        Leaf hit = locate(schema, values, message);
        return List.of(new RowIssue(1, hit == null ? null : hit.id(),
            hit == null ? null : hit.label(), "IMPORT_VALIDATION_FAILED",
            humanize(message, hit)));
    }

    /** 先按"消息以 label 开头"找（校验器的主要写法），再退化为"消息里包含 label"。 */
    private static Leaf locate(JsonNode schema, Map<String, Object> values, String message) {
        String text = message == null ? "" : message;
        List<Leaf> leaves = leaves(schema);
        Leaf best = null;
        for (Leaf leaf : leaves) {
            if (leaf.label() != null && !leaf.label().isBlank() && text.startsWith(leaf.label())
                && (best == null || leaf.label().length() > best.label().length())) {
                best = leaf;
            }
        }
        if (best != null) return best;
        for (Leaf leaf : leaves) {
            if (leaf.label() != null && !leaf.label().isBlank() && text.contains(leaf.label())
                && (best == null || leaf.label().length() > best.label().length())) {
                best = leaf;
            }
        }
        if (best != null) return best;
        // 兜底：必填类消息本身不带字段名（"请完成检查项"、矩阵的"行 / 列 is required"）。
        if (text.endsWith(" is required") || "请完成检查项".equals(text)) {
            for (Leaf leaf : leaves) {
                if (leaf.required() && !filled(values.get(leaf.id()))) return leaf;
            }
        }
        return null;
    }

    private static String humanize(String message, Leaf leaf) {
        String text = message == null ? "" : message.trim();
        if (text.endsWith(" is required")) {
            return leaf == null
                ? "有必填项没有填写，请补全后再提交"
                : "「" + leaf.label() + "」为必填项，请填写后再提交";
        }
        if ("请完成检查项".equals(text)) {
            String label = leaf == null ? "检查项" : leaf.label();
            return "「" + label + "」还有必填的检查项没做：请至少为每个必填项选择「结果」";
        }
        if (leaf != null && !text.contains(leaf.label())) {
            return "「" + leaf.label() + "」" + text;
        }
        if (leaf != null && text.endsWith(" must be a number")) {
            return "「" + leaf.label() + "」只能填数字";
        }
        return text;
    }

    private static List<Leaf> leaves(JsonNode schema) {
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(schema, leaves);
        return leaves;
    }

    private static void collectLeaves(JsonNode nodes, List<Leaf> leaves) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
                collectLeaves(node.path("children"), leaves);
                continue;
            }
            if ("description".equals(type)) continue;
            String id = node.path("id").asText("");
            if (id.isBlank()) continue;
            boolean required = node.path("rules").path("required")
                .asBoolean(node.path("props").path("required").asBoolean(false));
            leaves.add(new Leaf(id, FormImportSupport.label(node), required));
        }
    }

    private static boolean filled(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof java.util.Collection<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private record Leaf(String id, String label, boolean required) { }

    /** 校验失败：带 failures 的 422。控制器把它原样翻成响应体，前端据此定位字段。 */
    public static final class ManualImportFailure extends BizException {

        private final transient List<RowIssue> failures;

        public ManualImportFailure(List<RowIssue> failures) {
            super("IMPORT_VALIDATION_FAILED", failures.isEmpty()
                ? "数据校验未通过，请检查后重试" : failures.get(0).message());
            this.failures = List.copyOf(failures);
        }

        public List<RowIssue> failures() {
            return failures;
        }

        /** 供 422 响应体用的 Map 形态（避免控制器再查 Bean 属性）。 */
        public List<Map<String, Object>> failureMaps() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (RowIssue failure : failures) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("row", failure.row());
                item.put("fieldId", failure.fieldId());
                item.put("fieldLabel", failure.fieldLabel());
                item.put("code", failure.code());
                item.put("message", failure.message());
                result.add(item);
            }
            return result;
        }
    }
}
