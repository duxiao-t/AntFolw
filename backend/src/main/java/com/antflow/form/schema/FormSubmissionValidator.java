package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.options.OptionRuntimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 提交期的值校验：这份数据能不能收？
 *
 * <p>与 {@code FormSchemaValidator} 的分工：那边答"schema 配得对吗"（{@code BAD_SCHEMA}），
 * 这里答"填的值对吗"（{@code FORM_DATA_INVALID}）。两者必须共用同一套节点树与可见性语义，
 * 否则会出现"发布时允许、提交时说非法"的死循环。四条容易改错的规则：
 * <ol>
 *   <li>**隐藏字段不校验**（{@link com.antflow.form.schema.FormVisibility}）：条件不满足的字段
 *       页面上根本不显示，报它必填等于让用户去填一个看不见的框。</li>
 *   <li>**`rules` 覆盖 `props`**：设计器把约束写进 {@code rules}（新）或 {@code props}（旧），
 *       {@code validationNode} 先把 rules 摊进 props，后面的校验只看 props。</li>
 *   <li>**外部选项按绑定判定**：{@code optionSource: {}} 走内置 options，不能当成外部源去要求 versionId。</li>
 *   <li>**明细行整行可见性单独算**：行的可见性来自行内字段的值，不能复用外层 values。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormSubmissionValidator {

    /** 与 FormDefinitionService 同一真源：容器类型只从 FieldTypeCatalog 派生，不手写清单。 */
    private static final Set<String> CONTAINER_TYPES =
        FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER);

    private final FormSchemaParser schemaParser;
    private final FormSchemaJson schemaJson;
    private final FormVisibility visibility;
    private final FormMatrixRules matrixRules;
    private final OptionRuntimeService optionRuntimeService;
    private final ObjectMapper json;

    public void validateSubmission(String schema, Object data) {
        var root = schemaParser.parseSchema(schema);
        Map<?, ?> values = schemaJson.valueMap(data);
        Set<String> visibleIds = visibility.visibleNodeIds(root, values);
        for (var node : root) {
            validateNodeValue(node, values, visibleIds);
        }
    }

    /** 只校验指定字段集合（节点级字段权限用）：容器递归，其余字段跳过。 */
    public void validateSubmission(String schema, Object data, Set<String> fieldIds) {
        var root = schemaParser.parseSchema(schema);
        Map<?, ?> values = schemaJson.valueMap(data);
        Set<String> visibleIds = visibility.visibleNodeIds(root, values);
        for (var node : root) {
            validateNodeValue(node, values, fieldIds, visibleIds);
        }
    }

    public void validateNodeValue(com.fasterxml.jackson.databind.JsonNode node,
                                   Map<?, ?> values, Set<String> fieldIds,
                                   Set<String> visibleIds) {
        node = validationNode(node);
        if (!visibleIds.contains(node.path("id").asText())) {
            return;
        }
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type)) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> validateNodeValue(child, values, fieldIds, visibleIds));
            }
            return;
        }
        if ("table_list".equals(type)) {
            if (containsAcceptedField(node.path("children"), fieldIds)) {
                validateTableValue(node, values.get(node.path("id").asText()), fieldIds);
            }
            return;
        }
        if (!fieldIds.contains(node.path("id").asText())) {
            return;
        }
        validateNodeValue(node, values, visibleIds);
    }

    public void validateNodeValue(com.fasterxml.jackson.databind.JsonNode node, Map<?, ?> values,
                                   Set<String> visibleIds) {
        node = validationNode(node);
        if (!visibleIds.contains(node.path("id").asText())) {
            return;
        }
        if (CONTAINER_TYPES.contains(node.path("type").asText(""))) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> validateNodeValue(child, values, visibleIds));
            }
            return;
        }
        Object value = values.get(node.path("id").asText());
        String type = node.path("type").asText();
        if ("description".equals(type)) return;
        if ("table_list".equals(type)) {
            validateTableValue(node, value, null);
            return;
        }
        var props = node.path("props");
        var rules = node.path("rules");
        if ("matrix_fill".equals(node.path("type").asText())) {
            matrixRules.validateMatrixSubmission(node, value);
            return;
        }
        boolean required = rules.path("required").asBoolean(props.path("required").asBoolean(false));
        if (required && FormVisibility.isEmpty(value)) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " is required");
        }
        validateValueShape(node, value);
        if ("dept_picker".equals(type) && !FormVisibility.isEmpty(value)) {
            validateDepartmentPickerValue(node, value);
        }
        // 判定必须与发布校验一致：`optionSource: {}` 是设计器"选了源还没选版本"的中途状态，
        // 发布时按未绑定（走内置 options）处理；这里若按 isObject 当成外部绑定去校验，就会出现
        // **表单能发布、却填不了**（提交时 requireVersion 因缺 sourceId 报 BAD_SCHEMA）。
        if (Set.of("select", "multi_select").contains(type) && !FormVisibility.isEmpty(value)
            && com.antflow.form.options.OptionRuntimeService.isBound(node.path("props").path("optionSource"))
            ) {
            if (optionRuntimeService == null) {
                throw new BizException("FORM_DATA_INVALID", "共享选项服务不可用");
            }
            optionRuntimeService.validateValue(node, value, values);
        }
        int maxLength = rules.path("maxLength").asInt(props.path("maxLength").asInt(-1));
        if (maxLength > -1 && value instanceof String s && s.length() > maxLength) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxLength");
        }
        int minChecked = props.path("minChecked").asInt(-1);
        int maxChecked = props.path("maxChecked").asInt(props.path("maxSelected").asInt(-1));
        if (value instanceof List<?> list) {
            if (minChecked > -1 && list.size() < minChecked) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " below minChecked");
            }
            if (maxChecked > -1 && list.size() > maxChecked) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxChecked");
            }
        }
        var children = node.path("children");
        if (children.isArray()) {
            children.forEach(child -> validateNodeValue(child, values, visibleIds));
        }
        if ("scan_code".equals(type) && !FormVisibility.isEmpty(value) && !(value instanceof String)) {
            throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " must be a string");
        }
        if ("audio_upload".equals(type) && !FormVisibility.isEmpty(value)) {
            if (!(value instanceof List<?> files) || files.stream().anyMatch(file ->
                !(file instanceof Map<?, ?> item) || !(item.get("id") instanceof String))) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " must be a file list");
            }
            int maxCount = props.path("maxCount").asInt(3);
            int maxDuration = props.path("maxDuration").asInt(60);
            if (files.size() > maxCount) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds maxCount");
            }
            for (Object file : files) {
                Object duration = ((Map<?, ?>) file).get("durationSeconds");
                if (duration != null && (!(duration instanceof Number seconds)
                    || !Double.isFinite(seconds.doubleValue()) || seconds.doubleValue() < 0
                    || seconds.doubleValue() > maxDuration)) {
                    throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " has invalid duration");
                }
            }
        }
        if ("location".equals(type) && !FormVisibility.isEmpty(value)) {
            if (!(value instanceof Map<?, ?> location)
                || !(location.get("latitude") instanceof Number latitude)
                || !(location.get("longitude") instanceof Number longitude)
                || latitude.doubleValue() < -90 || latitude.doubleValue() > 90
                || longitude.doubleValue() < -180 || longitude.doubleValue() > 180
                || !Double.isFinite(latitude.doubleValue()) || !Double.isFinite(longitude.doubleValue())
                || (location.get("accuracy") != null
                    && (!(location.get("accuracy") instanceof Number accuracy)
                        || !Double.isFinite(accuracy.doubleValue()) || accuracy.doubleValue() < 0))
                || (location.get("coordinateSystem") != null
                    && !Set.of("WGS84", "GCJ02").contains(location.get("coordinateSystem")))) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " has invalid coordinates");
            }
        }
        if (Set.of("number", "money").contains(node.path("type").asText()) && !FormVisibility.isEmpty(value)) {
            java.math.BigDecimal number;
            try {
                number = new java.math.BigDecimal(String.valueOf(value));
            } catch (NumberFormatException error) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " must be a number");
            }
            var min = props.path("min");
            var max = props.path("max");
            if (min.isNumber() && number.compareTo(min.decimalValue()) < 0) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " is below min");
            }
            if (max.isNumber() && number.compareTo(max.decimalValue()) > 0) {
                throw new BizException("FORM_DATA_INVALID", node.path("label").asText(node.path("id").asText()) + " exceeds max");
            }
        }
    }

    private JsonNode validationNode(JsonNode node) {
        if (!node.path("rules").isObject()) return node;
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) node.deepCopy();
        var props = node.path("props").isObject()
            ? (com.fasterxml.jackson.databind.node.ObjectNode) node.path("props").deepCopy()
            : json.createObjectNode();
        props.setAll((com.fasterxml.jackson.databind.node.ObjectNode) node.path("rules"));
        copy.set("props", props);
        return copy;
    }

    public void validateValueShape(JsonNode node, Object value) {
        String type = node.path("type").asText();
        if ("description".equals(type) || value == null
            || value instanceof String text && text.isBlank()
            || value instanceof List<?> list && list.isEmpty()) return;
        JsonNode props = node.path("props");
        String label = node.path("label").asText(node.path("id").asText());
        boolean valid = switch (type) {
            case "text", "textarea", "search", "scan_code", "date", "time" -> value instanceof String;
            case "number", "money" -> value instanceof Number || value instanceof String;
            case "switch" -> value instanceof Boolean;
            case "select", "radio" -> value instanceof String || value instanceof Number;
            case "multi_select", "checkbox" -> value instanceof List<?> list
                && list.stream().allMatch(item -> item instanceof String || item instanceof Number);
            case "date_range" -> value instanceof List<?> list && list.size() == 2
                && list.stream().allMatch(item -> item instanceof String text && !text.isBlank());
            case "user_picker" -> validUserPicker(value, props.path("multiple").asBoolean(false));
            case "file_upload", "image_upload", "video_upload", "audio_upload" -> value instanceof List<?> files
                && files.stream().allMatch(item -> item instanceof Map<?, ?> file
                    && file.get("id") instanceof String
                    && (file.get("status") == null || "READY".equals(file.get("status"))));
            case "checklist" -> value instanceof List<?> entries
                && entries.stream().allMatch(item -> item instanceof Map<?, ?>);
            default -> true;
        };
        if (!valid) throw new BizException("FORM_DATA_INVALID", label + "格式不正确");
        if (value instanceof String text) {
            if (props.path("minLength").isNumber() && text.length() < props.path("minLength").asInt()) {
                throw new BizException("FORM_DATA_INVALID", label + " below minLength");
            }
            if (props.path("pattern").isTextual()) {
                try {
                    if (!java.util.regex.Pattern.compile(props.path("pattern").asText()).matcher(text).find()) {
                        throw new BizException("FORM_DATA_INVALID", label + "格式不正确");
                    }
                } catch (java.util.regex.PatternSyntaxException exception) {
                    throw new BizException("BAD_SCHEMA", label + "校验规则无效");
                }
            }
        }
        if (value instanceof List<?> list && props.path("maxCount").isNumber()
            && list.size() > props.path("maxCount").asInt()) {
            throw new BizException("FORM_DATA_INVALID", label + " exceeds maxCount");
        }
        if (Set.of("number", "money").contains(type) && props.path("precision").isNumber()) {
            try {
                if (new java.math.BigDecimal(value.toString()).stripTrailingZeros().scale()
                    > props.path("precision").asInt()) {
                    throw new BizException("FORM_DATA_INVALID", label + " exceeds precision");
                }
            } catch (NumberFormatException exception) {
                throw new BizException("FORM_DATA_INVALID", label + " must be a number");
            }
        }
        if ("checklist".equals(type)) validateChecklistValue(node, (List<?>) value);
    }

    private static boolean validUserPicker(Object value, boolean multiple) {
        if (multiple && value instanceof List<?> list) {
            return list.stream().allMatch(item -> validUserPicker(item, false));
        }
        return value instanceof Number number && Double.isFinite(number.doubleValue())
            && number.doubleValue() > 0 && number.doubleValue() <= 9007199254740991d
            && number.doubleValue() == Math.rint(number.doubleValue());
    }

    private void validateChecklistValue(JsonNode node, List<?> entries) {
        JsonNode props = node.path("props");
        Map<String, Map<?, ?>> byId = new java.util.LinkedHashMap<>();
        for (Object raw : entries) {
            Map<?, ?> entry = (Map<?, ?>) raw;
            Object id = entry.get("id") == null ? entry.get("itemId") : entry.get("id");
            if (id != null) byId.put(String.valueOf(id), entry);
        }
        int index = 0;
        for (JsonNode item : props.path("items")) {
            Map<?, ?> entry = byId.get(item.path("id").asText("item-" + index++));
            Object status = entry == null ? null
                : entry.get("status") == null ? entry.get("result") : entry.get("status");
            if (item.path("required").asBoolean(false) && FormVisibility.isEmpty(status)) {
                throw new BizException("FORM_DATA_INVALID", "请完成检查项");
            }
            if (entry == null) continue;
            Object images = entry.get("images") == null ? entry.get("photos") : entry.get("images");
            int photos = images instanceof List<?> list ? list.size() : 0;
            if (photos > props.path("photoMaxCount").asInt(9)) {
                throw new BizException("FORM_DATA_INVALID", "检查项照片超过上限");
            }
            Object description = entry.get("description") == null ? entry.get("remark") : entry.get("description");
            if (props.path("allowDescription").asBoolean(true) && !FormVisibility.isEmpty(status)
                && props.path("descriptionRequiredByResult").path(String.valueOf(status)).asBoolean(false)
                && FormVisibility.isEmpty(description) && photos == 0) {
                throw new BizException("FORM_DATA_INVALID", "检查项描述必填");
            }
        }
        if (props.path("required").asBoolean(false) && entries.stream().noneMatch(raw -> {
            Map<?, ?> entry = (Map<?, ?>) raw;
            return !FormVisibility.isEmpty(entry.get("status") == null ? entry.get("result") : entry.get("status"));
        })) throw new BizException("FORM_DATA_INVALID", "请完成检查项");
    }

    private boolean containsAcceptedField(JsonNode nodes, Set<String> fieldIds) {
        if (!nodes.isArray()) return false;
        for (var child : nodes) {
            String type = child.path("type").asText("");
            if (CONTAINER_TYPES.contains(type) || "table_list".equals(type)) {
                if (containsAcceptedField(child.path("children"), fieldIds)) return true;
            } else if (fieldIds.contains(child.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    public void validateTableValue(JsonNode node, Object value, Set<String> fieldIds) {
        String label = node.path("label").asText(node.path("id").asText());
        if (value != null && !(value instanceof List<?>)) {
            throw new BizException("FORM_DATA_INVALID", label + " must be a row list");
        }
        List<?> rows = value instanceof List<?> list ? list : List.of();
        JsonNode props = node.path("props");
        JsonNode rules = node.path("rules");
        boolean required = rules.path("required").asBoolean(
            props.path("required").asBoolean(false));
        if (rows.isEmpty() && !required) return;
        int minRows = Math.max(0, props.path("minRows").asInt(required ? 1 : 0));
        int maxRows = props.path("maxRows").canConvertToInt()
            ? props.path("maxRows").asInt() : Integer.MAX_VALUE;
        if (rows.size() < minRows) {
            throw new BizException("FORM_DATA_INVALID", label + " below minRows");
        }
        if (rows.size() > maxRows) {
            throw new BizException("FORM_DATA_INVALID", label + " exceeds maxRows");
        }
        JsonNode children = node.path("children");
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> row)) {
                throw new BizException("FORM_DATA_INVALID", label + " contains an invalid row");
            }
            Set<String> rowVisibleIds = visibility.visibleNodeIds(children, row);
            children.forEach(child -> {
                if (fieldIds == null) {
                    validateNodeValue(child, row, rowVisibleIds);
                } else {
                    validateNodeValue(child, row, fieldIds, rowVisibleIds);
                }
            });
        }
    }

    public void validateDepartmentPickerValue(JsonNode node, Object value) {
        boolean multiple = node.path("props").path("multiple").asBoolean(false);
        if (!multiple && value instanceof List<?>) {
            throw invalidDepartmentPickerValue(node);
        }
        List<?> ids = value instanceof List<?> list ? list : List.of(value);
        if (ids.stream().anyMatch(id -> !isPositiveIntegralId(id))) {
            throw invalidDepartmentPickerValue(node);
        }
        int maxCount = node.path("props").path("maxCount").asInt(-1);
        if (multiple && maxCount > 0 && ids.size() > maxCount) {
            throw new BizException("FORM_DATA_INVALID",
                node.path("label").asText(node.path("id").asText()) + " exceeds maxCount");
        }
    }

    private BizException invalidDepartmentPickerValue(JsonNode node) {
        return new BizException("FORM_DATA_INVALID",
            node.path("label").asText(node.path("id").asText())
                + " must contain positive department ids");
    }

    private static boolean isPositiveIntegralId(Object value) {
        if (!(value instanceof Number number)) return false;
        try {
            return new java.math.BigDecimal(number.toString()).longValueExact() > 0;
        } catch (ArithmeticException | NumberFormatException error) {
            return false;
        }
    }
}
