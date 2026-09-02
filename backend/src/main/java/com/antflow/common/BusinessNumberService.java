package com.antflow.common;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusinessNumberService {
    private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,31}$");
    private static final Pattern SAFE_VALUE = Pattern.compile("^[^/\\\\\\p{Cntrl}]{1,64}$");
    private static final Set<String> DATE_PATTERNS = Set.of("yyyy", "yyyyMM", "yyyyMMdd", "yyMMdd");
    private static final Set<String> SCALAR_FIELDS = Set.of("text", "number", "money", "radio",
        "select", "search", "date", "time", "user_picker", "dept_picker", "scan_code");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FormalNumberService legacy;

    public String next(FormDefinition form, Object data) {
        Rule rule = rule(form);
        if (rule == null) return legacy.businessNo();
        Map<?, ?> values = data instanceof Map<?, ?> map ? map : Map.of();
        Map<String, String> fields = schemaFields(form.getSchema());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long sequence = nextCounter(form.getId(), periodKey(rule.reset(), today));
        StringBuilder result = new StringBuilder(rule.namespace());
        for (JsonNode part : rule.parts()) {
            switch (part.path("type").asText()) {
                case "LITERAL" -> result.append(part.path("value").asText());
                case "FIELD" -> {
                    String fieldId = part.path("fieldId").asText();
                    result.append(normalizeField(values.get(fieldId), fields.get(fieldId)));
                }
                case "DATE" -> result.append(today.format(DateTimeFormatter.ofPattern(
                    part.path("pattern").asText())));
                case "SEQUENCE" -> result.append(String.format("%0" + part.path("width").asInt() + "d",
                    sequence));
                default -> throw new BizException("BUSINESS_NUMBER_INVALID", "流水号包含未知部件");
            }
        }
        if (result.length() > 128) {
            throw new BizException("BUSINESS_NUMBER_TOO_LONG", "生成的流水号不能超过 128 个字符");
        }
        return result.toString();
    }

    public void validate(FormDefinition form) {
        Rule rule = rule(form);
        if (rule == null) return;
        if (!NAMESPACE.matcher(rule.namespace()).matches()) {
            throw new BizException("BUSINESS_NUMBER_NAMESPACE_INVALID", "流水号唯一前缀格式不正确");
        }
        Long duplicate = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_form_definition
            WHERE id <> ? AND status = 'PUBLISHED' AND deleted = 0
              AND settings @> '{"businessNumber":{"enabled":true}}'::jsonb
              AND lower(settings #>> '{businessNumber,namespace}') = lower(?)
            """, Long.class, form.getId(), rule.namespace());
        if (duplicate != null && duplicate > 0) {
            throw new BizException("BUSINESS_NUMBER_NAMESPACE_EXISTS", "流水号唯一前缀已被其他表单使用");
        }
        Map<String, String> fields = schemaFields(form.getSchema());
        int sequences = 0;
        boolean compatibleDate = "NONE".equals(rule.reset());
        for (JsonNode part : rule.parts()) {
            String type = part.path("type").asText();
            switch (type) {
                case "LITERAL" -> {
                    String value = part.path("value").asText();
                    if (value.isEmpty() || value.length() > 32 || !SAFE_VALUE.matcher(value).matches()) {
                        throw new BizException("BUSINESS_NUMBER_LITERAL_INVALID", "流水号固定文本格式不正确");
                    }
                }
                case "FIELD" -> {
                    String fieldId = part.path("fieldId").asText();
                    if (!SCALAR_FIELDS.contains(fields.get(fieldId))) {
                        throw new BizException("BUSINESS_NUMBER_FIELD_INVALID", "流水号引用了不支持的表单字段");
                    }
                }
                case "DATE" -> {
                    String pattern = part.path("pattern").asText();
                    if (!DATE_PATTERNS.contains(pattern)) {
                        throw new BizException("BUSINESS_NUMBER_DATE_INVALID", "流水号日期格式不支持");
                    }
                    compatibleDate |= switch (rule.reset()) {
                        case "DAILY" -> pattern.contains("dd");
                        case "MONTHLY" -> pattern.contains("MM");
                        case "YEARLY" -> pattern.contains("yy");
                        default -> true;
                    };
                }
                case "SEQUENCE" -> {
                    sequences++;
                    int width = part.path("width").asInt();
                    if (width < 1 || width > 12) {
                        throw new BizException("BUSINESS_NUMBER_SEQUENCE_INVALID", "流水号序号位数必须为 1 至 12");
                    }
                }
                default -> throw new BizException("BUSINESS_NUMBER_INVALID", "流水号包含未知部件");
            }
        }
        if (rule.parts().size() > 20 || sequences != 1 || !compatibleDate) {
            throw new BizException("BUSINESS_NUMBER_INVALID", "流水号必须包含一个序号，重置周期必须有对应日期");
        }
    }

    private Rule rule(FormDefinition form) {
        try {
            JsonNode config = json.readTree(form.getSettings() == null ? "{}" : form.getSettings())
                .path("businessNumber");
            if (!config.path("enabled").asBoolean(false)) return null;
            JsonNode parts = config.path("parts");
            if (!parts.isArray()) throw new BizException("BUSINESS_NUMBER_INVALID", "流水号部件配置无效");
            String reset = config.path("reset").asText("NONE");
            if (!Set.of("NONE", "DAILY", "MONTHLY", "YEARLY").contains(reset)) {
                throw new BizException("BUSINESS_NUMBER_INVALID", "流水号重置周期无效");
            }
            return new Rule(config.path("namespace").asText().trim(), parts, reset);
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException("BUSINESS_NUMBER_INVALID", "流水号配置无法解析");
        }
    }

    private long nextCounter(long formId, String period) {
        Long value = jdbc.query("""
            INSERT INTO t_form_number_counter(form_def_id, period_key, value)
            VALUES (?, ?, 1)
            ON CONFLICT (form_def_id, period_key)
            DO UPDATE SET value = t_form_number_counter.value + 1
            RETURNING value
            """, rs -> rs.next() ? rs.getLong(1) : null, formId, period);
        if (value == null) throw new BizException("BUSINESS_NUMBER_FAILED", "流水号生成失败");
        return value;
    }

    private static String periodKey(String reset, LocalDate date) {
        return switch (reset) {
            case "DAILY" -> date.format(DateTimeFormatter.BASIC_ISO_DATE);
            case "MONTHLY" -> date.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case "YEARLY" -> String.valueOf(date.getYear());
            default -> "ALL";
        };
    }

    private String normalizeField(Object raw, String type) {
        if (raw == null) throw new BizException("BUSINESS_NUMBER_FIELD_REQUIRED", "流水号引用字段不能为空");
        String value;
        if ("user_picker".equals(type)) {
            Long id = singleId(raw);
            value = id == null ? null : jdbc.query("SELECT employee_no FROM t_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        } else if ("dept_picker".equals(type)) {
            Long id = singleId(raw);
            value = id == null ? null : jdbc.query("SELECT name FROM t_department WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        } else if (raw instanceof Map<?, ?> map) {
            Object selected = map.containsKey("employeeNo") ? map.get("employeeNo")
                : map.containsKey("value") ? map.get("value")
                : map.containsKey("name") ? map.get("name") : map.get("label");
            value = String.valueOf(selected);
        } else if (raw instanceof Iterable<?> || raw.getClass().isArray()) {
            throw new BizException("BUSINESS_NUMBER_FIELD_INVALID", "流水号字段必须是单值");
        } else {
            value = String.valueOf(raw);
        }
        if (value == null) throw new BizException("BUSINESS_NUMBER_FIELD_REQUIRED", "流水号引用字段不能为空");
        value = value.trim().replaceAll("\\s+", "-");
        if (!SAFE_VALUE.matcher(value).matches()) {
            throw new BizException("BUSINESS_NUMBER_FIELD_INVALID", "流水号字段包含斜杠、控制字符或内容过长");
        }
        return value;
    }

    private static Long singleId(Object raw) {
        Object value = raw;
        if (raw instanceof java.util.Collection<?> values) {
            if (values.size() != 1) return null;
            value = values.iterator().next();
        }
        if (value instanceof Map<?, ?> map) value = map.get("id");
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException error) { return null; }
    }

    private Map<String, String> schemaFields(String schema) {
        try {
            Map<String, String> result = new HashMap<>();
            collectFields(json.readTree(schema == null ? "[]" : schema), result, new HashSet<>());
            return result;
        } catch (Exception error) {
            throw new BizException("BAD_SCHEMA", "表单结构无法解析");
        }
    }

    private void collectFields(JsonNode nodes, Map<String, String> result, Set<String> seen) {
        if (!nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String id = node.path("id").asText();
            if (!id.isBlank() && seen.add(id)) result.put(id, node.path("type").asText());
            collectFields(node.path("children"), result, seen);
        }
    }

    private record Rule(String namespace, JsonNode parts, String reset) { }
}
