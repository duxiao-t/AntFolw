package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 叶子字段的值校验：只回答"这一个字段填的值长什么样"，不负责走树。
 *
 * <p>为什么与 {@code FormSubmissionValidator} 分开：那边是**递归骨架**（容器下钻、明细行逐行重跑），
 * 这边是**叶子**（不读 {@code children}、不递归、不碰其它节点）。判据只有一条——
 * 这个方法自己会不会再进 {@code validateNodeValue}。会，就留在骨架；不会，就搬到这。
 * 于是"某种字段类型的值该长什么样"这类需求集中在一个文件里改，骨架那边只留遍历与编排。
 *
 * <p>三个容易踩的点：
 * <ol>
 *   <li>**"列表形状"也是值形状**：{@code multi_select}/{@code date_range}/{@code file_upload}
 *       要求 {@code value} 是列表，判的是值本身，不是子节点——所以它们属于这里，不属于骨架。</li>
 *   <li>**{@code pattern} 编译失败是 {@code BAD_SCHEMA} 不是 {@code FORM_DATA_INVALID}**：
 *       正则写坏了是**表单配错**（保存/发布时就该拦），不是用户填错。这两类 code 前端分支不同，
 *       改错会把"schema 写坏了"变成一次用户侧的 500。</li>
 *   <li>**清单的"逐项必填"与"整体必填"是两条规则**：{@code props.items[].required} 管每一项，
 *       末尾那段 {@code props.required} 管"至少完成一项"。只实现一条会让"必须完成检查项"
 *       在"一项都没填"时静默通过。</li>
 * </ol>
 */
@Component
public class FormFieldValueValidator {

    public void validateValueShape(JsonNode node, Object value) {
        String type = node.path("type").asText();
        if ("description".equals(type) || value == null
            || value instanceof String text && text.isBlank()
            || value instanceof List<?> list && list.isEmpty()) return;
        JsonNode props = node.path("props");
        String label = node.path("label").asText(node.path("id").asText());
        boolean valid = switch (type) {
            case "text", "textarea", "scan_code", "date", "time" -> value instanceof String;
            case "number", "money" -> value instanceof Number || value instanceof String;
            case "select" -> value instanceof String || value instanceof Number;
            case "multi_select" -> value instanceof List<?> list
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
