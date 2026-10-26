package com.antflow.form.importer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等键查询：文件里的「业务单号」在本表单/本公司是否已经存在。
 *
 * <p>单独一类的理由：这里只有三条 SQL，变化的触发点是"幂等规则改了"（例如 V62 增加 import_key
 * 唯一索引），与"单元格怎么解、列怎么对账"毫无关系；混在解析器里会让解析器重新带上数据库依赖。
 *
 * <p>两条口径：
 * <ol>
 *   <li>**只查文件里出现过的键**：整张表单的 import_key 可能有几十万条，全读进内存只为判重是自杀。</li>
 *   <li>**两种来源都算重复**：库里已有 import_key，或某个已有 business_no 与它相同
 *       （历史数据可能把外部单号填进了业务单号）。</li>
 * </ol>
 */
@Component
public class FormImportKeyLookup {

    private final JdbcTemplate jdbc;

    public FormImportKeyLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 文件里出现过的「业务单号」（去重）。 */
    Set<String> importKeys(List<Map<String, String>> rows) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            String value = row.get(FormImportTemplateService.KEY_COLUMN_ID);
            if (value != null && !value.isBlank()) keys.add(value.trim());
        }
        return keys;
    }

    Set<String> existingImportKeys(long formId, Set<String> keys) {
        if (keys.isEmpty()) return Set.of();
        Set<String> found = new LinkedHashSet<>();
        List<String> list = new ArrayList<>(keys);
        for (int from = 0; from < list.size(); from += 500) {
            List<String> chunk = list.subList(from, Math.min(list.size(), from + 500));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(formId);
            args.addAll(chunk);
            found.addAll(jdbc.queryForList("""
                SELECT import_key FROM t_form_data
                WHERE form_def_id = ?
                  AND import_key IN (""" + marks + ")", String.class, args.toArray()));
        }
        return found;
    }

    Set<String> existingBusinessNos(Set<String> keys) {
        if (keys.isEmpty()) return Set.of();
        List<String> list = new ArrayList<>(keys);
        Set<String> found = new LinkedHashSet<>();
        for (int from = 0; from < list.size(); from += 500) {
            List<String> chunk = list.subList(from, Math.min(list.size(), from + 500));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            found.addAll(jdbc.queryForList(
                "SELECT business_no FROM t_form_data WHERE business_no IN (" + marks + ")",
                String.class, chunk.toArray()));
        }
        return found;
    }
}
