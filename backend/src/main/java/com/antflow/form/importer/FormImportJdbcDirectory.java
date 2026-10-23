package com.antflow.form.importer;

import com.antflow.form.importer.FormImportValueCodec.DeptRef;
import com.antflow.form.importer.FormImportValueCodec.UserRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 生产实现：一次导入最多 4 类查询（人员、部门、每个选项版本一次），全部走 IN 分批。
 *
 * <p>为什么不复用 {@code OptionSourceBinding}：那边按字段逐次查（还带联动过滤），
 * 适合"一次查一列"，不适合"一次导入把全表要用的字典装进来"。
 *
 * <p>从 {@code FormImportValueCodec} 里搬出来，因为它与"怎么读一个单元格"毫无关系：
 * 它只回答"一次导入要查哪些字典"。
 */
@RequiredArgsConstructor
final class FormImportJdbcDirectory implements FormImportValueCodec.Directory {
    private static final int CHUNK = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Override
    public List<UserRef> users(Collection<String> tokens) {
        Set<String> names = new LinkedHashSet<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            if (token.startsWith("id:")) {
                try {
                    ids.add(Long.parseLong(token.substring(3).trim()));
                } catch (NumberFormatException ignored) {
                    // 交给解码阶段报"id 必须是正整数"，这里只负责取数。
                }
            } else {
                names.add(token.trim());
            }
        }
        Map<Long, UserRef> found = new LinkedHashMap<>();
        for (List<String> chunk : chunks(new ArrayList<>(names))) {
            String marks = marks(chunk.size());
            Object[] args = new Object[chunk.size() * 3];
            for (int index = 0; index < chunk.size(); index++) {
                args[index] = chunk.get(index);
                args[chunk.size() + index] = chunk.get(index);
                args[chunk.size() * 2 + index] = chunk.get(index);
            }
            jdbc.query("SELECT id, employee_no, display_name FROM t_user WHERE employee_no IN ("
                    + marks + ") OR display_name IN (" + marks + ") OR username IN (" + marks + ")",
                rs -> {
                    UserRef user = user(rs);
                    found.putIfAbsent(user.id(), user);
                }, args);
        }
        for (List<Long> chunk : chunks(new ArrayList<>(ids))) {
            jdbc.query("SELECT id, employee_no, display_name FROM t_user WHERE id IN ("
                    + marks(chunk.size()) + ")",
                rs -> {
                    UserRef user = user(rs);
                    found.putIfAbsent(user.id(), user);
                }, chunk.toArray());
        }
        return new ArrayList<>(found.values());
    }

    @Override
    public List<DeptRef> departments() {
        return jdbc.query("SELECT id, name, parent_id FROM t_department ORDER BY path, id",
            (rs, row) -> new DeptRef(rs.getLong("id"), rs.getString("name"),
                nullableLong(rs, "parent_id")));
    }

    @Override
    public List<Map<String, String>> optionRows(long versionId) {
        return jdbc.query("SELECT data::text FROM t_option_data_source_row"
                + " WHERE version_id = ? ORDER BY row_no",
            (rs, row) -> readRow(rs.getString(1)), versionId);
    }

    private Map<String, String> readRow(String text) {
        try {
            return json.readValue(text, new TypeReference<Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("invalid option row", error);
        }
    }

    private static UserRef user(ResultSet rs) throws SQLException {
        return new UserRef(rs.getLong("id"), rs.getString("employee_no"),
            rs.getString("display_name"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int index = 0; index < values.size(); index += CHUNK) {
            chunks.add(values.subList(index, Math.min(values.size(), index + CHUNK)));
        }
        return chunks;
    }
}
