package com.antflow.form.options;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据源「版本」的只读查询与行映射（F3 职责抽取）。
 *
 * <p>从 751 行的 {@link OptionSourceService} 里把「查版本 / 读列 / 读行」这类纯读取关注点拆出来，
 * 让写入/生命周期编排的类不必再混着看 SQL 映射。无权限判断、无事务：权限与事务仍由调用方负责。
 */
@Component
@RequiredArgsConstructor
class OptionSourceVersionReader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** 按 id 取一个版本；不存在即 404 语义（与旧实现完全一致）。 */
    OptionSourceService.VersionView requireVersion(long versionId) {
        OptionSourceService.VersionView version = jdbc.query("""
            SELECT v.id, v.source_id, v.version_no, v.status, v.columns_json::text, v.row_count,
                   v.original_name, v.sha256, v.created_at, v.published_at, v.disabled_at,
                   v.note, publisher.display_name AS published_by_name
            FROM t_option_data_source_version v
            LEFT JOIN t_user publisher ON publisher.id = v.published_by
            WHERE v.id = ?
            """, rs -> rs.next() ? versionView(rs) : null, versionId);
        if (version == null) throw new BizException("OPTION_SOURCE_VERSION_NOT_FOUND", "数据版本不存在");
        return version;
    }

    List<OptionSourceService.VersionView> versions(long sourceId, boolean publishedOnly) {
        return jdbc.query("""
            SELECT v.id, v.source_id, v.version_no, v.status, v.columns_json::text, v.row_count,
                   v.original_name, v.sha256, v.created_at, v.published_at, v.disabled_at,
                   v.note, publisher.display_name AS published_by_name
            FROM t_option_data_source_version v
            -- 发布人只给显示名：这页是管理端，但登录账号没必要下发（同 D-20260924 的窄 DTO 口径）。
            LEFT JOIN t_user publisher ON publisher.id = v.published_by
            WHERE v.source_id = ? AND (? = false OR v.status = 'PUBLISHED')
            ORDER BY v.version_no DESC
            """, (rs, row) -> versionView(rs), sourceId, publishedOnly);
    }

    List<String> readColumns(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("invalid option source columns", error);
        }
    }

    Map<String, Object> readRow(String raw) {
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception error) {
            throw new IllegalStateException("invalid option row", error);
        }
    }

    /** 供调用方自定义 SQL（如取上一版）时复用同一套列映射。 */
    OptionSourceService.VersionView mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return versionView(rs);
    }

    private OptionSourceService.VersionView versionView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OptionSourceService.VersionView(rs.getLong("id"), rs.getLong("source_id"),
            rs.getInt("version_no"), rs.getString("status"), readColumns(rs.getString("columns_json")),
            rs.getInt("row_count"), rs.getString("original_name"), rs.getString("sha256"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getObject("disabled_at", OffsetDateTime.class), rs.getString("note"),
            rs.getString("published_by_name"));
    }
}
