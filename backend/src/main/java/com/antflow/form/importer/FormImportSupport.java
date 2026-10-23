package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入链路里跨类共用的几个小工具：错误构造、多值拆分、字段标签、JSON 读写。
 *
 * <p>抽出来的理由只有一条：这些方法原本是 {@link FormImportValueCodec} 与
 * {@code FormDataImportService} 的 private 静态方法，拆分后每个类都要用；复制 5 份等于让
 * 错误码与文案变成 5 个各自漂移的副本。
 *
 * <p>两条不变的口径：
 * <ul>
 *   <li>值错误一律 {@code IMPORT_VALUE_INVALID}，不支持的类型一律 {@code IMPORT_FIELD_UNSUPPORTED}；</li>
 *   <li>读 JSON 失败返回 {@code null}（由调用方给出"不是合法的 JSON"），写 JSON 失败直接报错——
 *       写不出去说明数据里有序列化不了的东西，不能静默降级。</li>
 * </ul>
 */
final class FormImportSupport {

    /** 多值分隔符：中英文逗号/分号、顿号、换行。产品口径见模板「填写说明」。 */
    static final String MULTI_SPLIT = "[、,，;；\\n\\r]";

    private FormImportSupport() { }

    static List<String> split(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.split(MULTI_SPLIT)) {
            String value = token.trim();
            if (!value.isEmpty()) tokens.add(value);
        }
        return tokens;
    }

    static long parseId(String text, String label) {
        try {
            long id = Long.parseLong(text.trim());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException error) {
            throw invalid(label, "id 必须是正整数：" + text);
        }
    }

    static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    static String label(JsonNode field) {
        return field.path("label").asText(field.path("id").asText(""));
    }

    /** 读不出来返回 {@code null}：调用方要按"不是合法的 JSON"报 IMPORT_VALUE_INVALID。 */
    static JsonNode readJson(ObjectMapper json, String text) {
        try {
            return json.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return null;
        }
    }

    static String writeJson(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("IMPORT_VALUE_INVALID", "数据无法序列化为 JSON");
        }
    }

    static BizException invalid(String label, String message) {
        return new BizException("IMPORT_VALUE_INVALID", "「" + label + "」" + message);
    }

    /** 配置里的候选名称，用于"只能填 A、B"这类可执行提示；超过 8 项只列前几个。 */
    static String labels(JsonNode items) {
        List<String> labels = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String label = item.path("label").asText("");
                if (!label.isBlank()) labels.add(label);
            }
        }
        if (labels.isEmpty()) return "配置里的选项";
        String joined = String.join("、", labels.size() > 8 ? labels.subList(0, 8) : labels);
        return joined + (labels.size() > 8 ? " 等" : "");
    }

    static BizException unsupported(String label, String message) {
        return new BizException("IMPORT_FIELD_UNSUPPORTED", "「" + label + "」" + message);
    }
}
