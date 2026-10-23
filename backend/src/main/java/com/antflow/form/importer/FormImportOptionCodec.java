package com.antflow.form.importer;

import com.antflow.form.options.OptionRuntimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 选项族：select / radio / multi_select / checkbox（静态选项与外部选项源两种来源）。
 *
 * <p>规则是"界面看到 label、库里存 value"：先按 value 认，再按 label 反查；
 * 同一个 label 对应多个 value 时报候选而不是挑一个——补录数据挂错选项比报错更难发现。
 *
 * <p>外部选项源的行来自 {@link FormImportValueCodec.Prefetch}（一次性预取），
 * 这里**不查库**：2 万行的表逐行查一次选项源会把接口拖成分钟级。
 */
@Component
public class FormImportOptionCodec {

    private final ObjectMapper json;

    public FormImportOptionCodec(ObjectMapper json) {
        this.json = json;
    }

    Object singleOption(JsonNode field, String label, String text,
                        FormImportValueCodec.Prefetch prefetch) {
        List<Option> options = options(field, prefetch);
        if (options.isEmpty()) throw FormImportSupport.invalid(label, "字段没有可用的选项配置，无法导入");
        return resolveOption(options, label, text.trim());
    }

    List<Object> multiOption(JsonNode field, String label, String text,
                             FormImportValueCodec.Prefetch prefetch) {
        List<Option> options = options(field, prefetch);
        if (options.isEmpty()) throw FormImportSupport.invalid(label, "字段没有可用的选项配置，无法导入");
        List<Object> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : FormImportSupport.split(text)) {
            Object value = resolveOption(options, label, token);
            if (seen.add(String.valueOf(value))) values.add(value);
        }
        return values;
    }

    /** 空选项值只在"同一个 label 对应多个 value"时才会返回 null，调用方会报错。 */
    private Object resolveOption(List<Option> options, String label, String token) {
        if (token.isBlank()) return null;
        for (Option option : options) {
            if (option.key().equals(token)) return option.value();
        }
        List<Option> hits = options.stream().filter(option -> option.label().equals(token)).toList();
        if (hits.isEmpty()) {
            throw FormImportSupport.invalid(label, "选项「" + token + "」不存在");
        }
        Set<String> distinct = new LinkedHashSet<>();
        hits.forEach(option -> distinct.add(String.valueOf(option.value())));
        if (distinct.size() > 1) {
            throw FormImportSupport.invalid(label, "选项「" + token + "」对应多个值（"
                + String.join("、", distinct) + "），请改填选项值");
        }
        return hits.get(0).value();
    }

    private record Option(Object value, String key, String label) { }

    private List<Option> options(JsonNode field, FormImportValueCodec.Prefetch prefetch) {
        List<Option> options = new ArrayList<>();
        JsonNode source = field.path("props").path("optionSource");
        if (OptionRuntimeService.isBound(source)) {
            long versionId = source.path("versionId").asLong(0);
            String valueColumn = source.path("valueColumn").asText("");
            String labelColumn = source.path("labelColumn").asText("");
            for (Map<String, String> row : prefetch.optionRows.getOrDefault(versionId, List.of())) {
                String value = row.get(valueColumn);
                if (value == null || value.isBlank()) continue;
                String label = row.get(labelColumn);
                options.add(new Option(value, value, label == null ? value : label));
            }
            return options;
        }
        for (JsonNode option : field.path("props").path("options")) {
            JsonNode raw = option.path("value");
            if (raw.isMissingNode() || raw.isNull()) continue;
            Object javaValue = json.convertValue(raw, Object.class);
            options.add(new Option(javaValue, raw.asText(), option.path("label").asText("")));
        }
        return options;
    }
}
