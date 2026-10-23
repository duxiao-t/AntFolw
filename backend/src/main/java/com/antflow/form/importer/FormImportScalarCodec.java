package com.antflow.form.importer;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 标量族：text / textarea / search / scan_code / number / money / date / date_range / time / switch。
 *
 * <p>这一族的共同点：单元格文本自己就能表达全部语义，不需要查任何字典。因此这里**没有**
 * {@link com.antflow.form.importer.FormImportValueCodec.Prefetch} 参数——加一种标量类型只改本类。
 *
 * <p>两处容易改错：
 * <ol>
 *   <li>文本类**不 trim**：用户填的前后空格是内容，裁掉就和表单里看到的不一致。</li>
 *   <li>日期/时间接受历史台账里的多种写法（2024/1/5、20240105），但输出统一成 ISO 形态——
 *       落库的值必须与手动填表一致，否则统计按字符串比较时会漏。</li>
 * </ol>
 */
@Component
public class FormImportScalarCodec {

    private static final Set<String> TRUE_WORDS = Set.of(
        "是", "开", "开启", "启用", "true", "yes", "y", "on", "1");
    private static final Set<String> FALSE_WORDS = Set.of(
        "否", "关", "关闭", "停用", "false", "no", "n", "off", "0");

    static java.math.BigDecimal number(String label, String text) {
        String value = text.trim();
        if (value.contains(",") || value.contains("，")) {
            throw FormImportSupport.invalid(label, "不要使用千分位");
        }
        try {
            return new java.math.BigDecimal(value);
        } catch (NumberFormatException error) {
            throw FormImportSupport.invalid(label,
                "不是合法数字「" + text + "」：只填数字，不要带千分位、单位或文字");
        }
    }

    LocalDate parseDate(String label, String text, JsonNode field) {
        List<String> formats = new ArrayList<>();
        String configured = field.path("props").path("format").asText("");
        if (!configured.isBlank()) formats.add(configured);
        formats.addAll(List.of("yyyy-M-d", "yyyy/M/d", "yyyyMMdd", "yyyy.M.d", "yyyy年M月d日"));
        for (String format : formats) {
            try {
                return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern(format));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 换下一种：模板里写的是通用格式，历史台账常混着 2024/1/5、20240105。
            }
        }
        throw FormImportSupport.invalid(label,
            "不是合法日期「" + text + "」：请写成 yyyy-MM-dd（如 2024-01-31）");
    }

    String parseTime(String label, String text, JsonNode field) {
        List<String> formats = new ArrayList<>();
        String configured = field.path("props").path("format").asText("");
        if (!configured.isBlank()) formats.add(configured);
        formats.addAll(List.of("H:mm:ss", "H:mm", "HH:mm:ss", "HH:mm"));
        for (String format : formats) {
            try {
                LocalTime time = LocalTime.parse(text.trim(), DateTimeFormatter.ofPattern(format));
                boolean seconds = format.contains("ss") || text.chars().filter(ch -> ch == ':').count() > 1;
                return time.format(DateTimeFormatter.ofPattern(seconds ? "HH:mm:ss" : "HH:mm"));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 同上。
            }
        }
        throw FormImportSupport.invalid(label,
            "不是合法时间「" + text + "」：请写成 HH:mm 或 HH:mm:ss（如 09:30）");
    }

    List<String> dateRange(String label, String text, JsonNode field) {
        String[] parts = text.split("[~～]", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw FormImportSupport.invalid(label, "日期区间必须写成「起~止」");
        }
        return List.of(parseDate(label, parts[0], field).toString(),
            parseDate(label, parts[1], field).toString());
    }

    Boolean bool(String label, String text, JsonNode field) {
        String value = text.trim();
        String checked = field.path("props").path("checkedText").asText("是");
        String unchecked = field.path("props").path("uncheckedText").asText("否");
        if (value.equalsIgnoreCase(checked)) return true;
        if (value.equalsIgnoreCase(unchecked)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (TRUE_WORDS.contains(lower)) return true;
        if (FALSE_WORDS.contains(lower)) return false;
        throw FormImportSupport.invalid(label, "只能填「" + checked + "」或「" + unchecked + "」");
    }
}
