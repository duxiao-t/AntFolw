package com.antflow.form.options;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class OptionSourceService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_COLUMNS = 50;
    private static final int MAX_CELL_LENGTH = 500;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;
    private final AuditService audit;

    public List<SourceSummary> list() {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        return jdbc.query("""
            SELECT source.id, source.code, source.name, source.status, source.version,
                   source.updated_at,
                   published.id AS published_version_id,
                   published.version_no AS published_version_no,
                   published.row_count,
                   draft.id AS draft_version_id
            FROM t_option_data_source source
            LEFT JOIN LATERAL (
              SELECT * FROM t_option_data_source_version version_row
              WHERE version_row.source_id = source.id AND version_row.status = 'PUBLISHED'
              ORDER BY version_row.version_no DESC LIMIT 1
            ) published ON true
            LEFT JOIN t_option_data_source_version draft
              ON draft.source_id = source.id AND draft.status = 'DRAFT'
            ORDER BY source.updated_at DESC, source.id DESC
            """, (rs, row) -> new SourceSummary(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("status"), rs.getInt("version"),
            rs.getObject("updated_at", OffsetDateTime.class),
            nullableLong(rs, "published_version_id"), nullableInt(rs, "published_version_no"),
            nullableInt(rs, "row_count"), nullableLong(rs, "draft_version_id")));
    }

    public SourceDetail detail(long sourceId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        SourceSummary source = requireSource(sourceId);
        List<VersionView> versions = versions(sourceId, false);
        List<Long> userIds = jdbc.queryForList("""
            SELECT subject_id FROM t_option_data_source_grant
            WHERE source_id = ? AND subject_type = 'USER' ORDER BY subject_id
            """, Long.class, sourceId);
        List<Long> roleIds = jdbc.queryForList("""
            SELECT subject_id FROM t_option_data_source_grant
            WHERE source_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
            """, Long.class, sourceId);
        return new SourceDetail(source, versions, userIds, roleIds,
            source.publishedVersionId() == null && !isReferenced(sourceId));
    }

    @Transactional
    public SourceDetail create(SourceWrite request) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        if (request == null || request.code() == null || !CODE.matcher(request.code().trim()).matches()
            || request.name() == null || request.name().isBlank() || request.name().trim().length() > 128) {
            throw new BizException("OPTION_SOURCE_INVALID", "数据源编码或名称无效");
        }
        long actor = authorization.currentUserId();
        Long id;
        try {
            id = jdbc.queryForObject("""
                INSERT INTO t_option_data_source(code, name, created_by)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, request.code().trim(), request.name().trim(), actor);
        } catch (org.springframework.dao.DuplicateKeyException error) {
            throw new BizException("OPTION_SOURCE_CODE_EXISTS", "数据源编码已存在");
        }
        audit.success("form.option_source.create", "OPTION_SOURCE", id,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("code", "name")), Map.of());
        return detail(id);
    }

    @Transactional
    public SourceDetail replaceGrants(long sourceId, GrantWrite request) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        if (request == null || request.version() == null) {
            throw new BizException("OPTION_SOURCE_VERSION_REQUIRED", "数据源版本不能为空");
        }
        requireSubjects(request.userIds(), request.roleIds());
        int updated = jdbc.update("""
            UPDATE t_option_data_source SET version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, sourceId, request.version());
        if (updated != 1) throw new BizException("OPTION_SOURCE_VERSION_CONFLICT", "数据源已被其他人修改");
        jdbc.update("DELETE FROM t_option_data_source_grant WHERE source_id = ?", sourceId);
        long actor = authorization.currentUserId();
        request.userIds().forEach(id -> jdbc.update("""
            INSERT INTO t_option_data_source_grant(source_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            """, sourceId, id, actor));
        request.roleIds().forEach(id -> jdbc.update("""
            INSERT INTO t_option_data_source_grant(source_id, subject_type, subject_id, granted_by)
            VALUES (?, 'ROLE', ?, ?)
            """, sourceId, id, actor));
        audit.success("form.option_source.grant.update", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("userIds", "roleIds")),
            Map.of("userCount", request.userIds().size(), "roleCount", request.roleIds().size()));
        return detail(sourceId);
    }

    @Transactional
    public VersionView importDraft(long sourceId, MultipartFile file, String text, String sheetName) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        lockSource(sourceId);
        try {
            boolean hasFile = file != null && !file.isEmpty();
            ImportedTable table = readImport(file, text, sheetName);
            byte[] bytes = hasFile ? file.getBytes() : text.getBytes(StandardCharsets.UTF_8);
            String name = hasFile ? safeName(file.getOriginalFilename()) : "pasted-text.txt";
            jdbc.update("DELETE FROM t_option_data_source_version WHERE source_id = ? AND status = 'DRAFT'",
                sourceId);
            Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                FROM t_option_data_source_version WHERE source_id = ?
                """, Integer.class, sourceId);
            Long versionId = jdbc.queryForObject("""
                INSERT INTO t_option_data_source_version(
                    source_id, version_no, status, columns_json, row_count,
                    original_name, sha256, created_by)
                VALUES (?, ?, 'DRAFT', ?::jsonb, ?, ?, ?, ?) RETURNING id
                """, Long.class, sourceId, nextVersion, json.writeValueAsString(table.columns()),
                table.rows().size(), name, sha256(bytes), authorization.currentUserId());
            List<Object[]> batch = new ArrayList<>();
            for (int index = 0; index < table.rows().size(); index++) {
                batch.add(new Object[] {versionId, index + 1,
                    json.writeValueAsString(rowMap(table.columns(), table.rows().get(index)))});
            }
            jdbc.batchUpdate("""
                INSERT INTO t_option_data_source_row(version_id, row_no, data)
                VALUES (?, ?, ?::jsonb)
                """, batch);
            jdbc.update("UPDATE t_option_data_source SET updated_at = now() WHERE id = ?", sourceId);
            audit.success("form.option_source.import", "OPTION_SOURCE", sourceId,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("draftVersion")),
                Map.of("rowCount", table.rows().size(), "columnCount", table.columns().size()));
            return version(versionId);
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException("OPTION_SOURCE_IMPORT_INVALID", "无法导入内容，请检查文件格式后重试");
        }
    }

    public ImportPreview inspect(MultipartFile file, String text, String sheetName) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        try {
            List<String> sheets = new ArrayList<>();
            if (file != null && !file.isEmpty()) {
                if (file.getSize() > MAX_BYTES) throw new BizException("OPTION_SOURCE_TOO_LARGE", "导入文件不能超过 10 MB");
                String name = safeName(file.getOriginalFilename()).toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
                    try (var workbook = WorkbookFactory.create(file.getInputStream())) {
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) sheets.add(workbook.getSheetName(i));
                    }
                    if (sheets.size() > 1 && (sheetName == null || sheetName.isBlank())) {
                        return new ImportPreview(sheets, List.of(), 0, List.of());
                    }
                }
            }
            ImportedTable table = readImport(file, text, sheetName);
            return new ImportPreview(sheets, table.columns(), table.rows().size(),
                table.rows().stream().limit(20).map(row -> rowMap(table.columns(), row)).toList());
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException("OPTION_SOURCE_IMPORT_INVALID", "无法解析文件，请检查文件格式或是否加密");
        }
    }

    private ImportedTable readImport(MultipartFile file, String text, String sheetName) throws Exception {
        boolean hasFile = file != null && !file.isEmpty();
        if (hasFile == (text != null && !text.isBlank())) {
            throw new BizException("OPTION_SOURCE_IMPORT_REQUIRED", "请选择一个文件或粘贴文本");
        }
        if (hasFile && file.getSize() > MAX_BYTES) throw new BizException("OPTION_SOURCE_TOO_LARGE", "导入文件不能超过 10 MB");
        byte[] bytes = hasFile ? file.getBytes() : text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new BizException("OPTION_SOURCE_TOO_LARGE", "导入内容不能超过 10 MB");
        ImportedTable table = hasFile ? parseFile(safeName(file.getOriginalFilename()), bytes, sheetName)
            : parsePastedText(stripBom(text));
        validateTable(table);
        return table;
    }

    private String lockSourceRow(long sourceId) {
        String status = jdbc.query("SELECT status FROM t_option_data_source WHERE id = ? FOR UPDATE",
            rs -> rs.next() ? rs.getString(1) : null, sourceId);
        if (status == null) throw new BizException("OPTION_SOURCE_NOT_FOUND", "数据源不存在");
        return status;
    }

    private void lockSource(long sourceId) {
        String status = lockSourceRow(sourceId);
        if (!"ACTIVE".equals(status)) throw new BizException("OPTION_SOURCE_UNAVAILABLE", "数据源不存在或已停用");
    }

    @Transactional
    public VersionView publish(long sourceId, long versionId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        lockSource(sourceId);
        int updated = jdbc.update("""
            UPDATE t_option_data_source_version
            SET status = 'PUBLISHED', published_by = ?, published_at = now()
            WHERE id = ? AND source_id = ? AND status = 'DRAFT'
            """, authorization.currentUserId(), versionId, sourceId);
        if (updated != 1) throw new BizException("OPTION_SOURCE_DRAFT_NOT_FOUND", "待发布版本不存在");
        jdbc.update("UPDATE t_option_data_source SET version = version + 1, updated_at = now() WHERE id = ?",
            sourceId);
        audit.success("form.option_source.publish", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("publishedVersion")), Map.of("versionId", versionId));
        return version(versionId);
    }

    @Transactional
    public SourceSummary disable(long sourceId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        int updated = jdbc.update("""
            UPDATE t_option_data_source
            SET status = 'DISABLED', version = version + 1, updated_at = now()
            WHERE id = ? AND status = 'ACTIVE'
            """, sourceId);
        if (updated == 0) requireSource(sourceId);
        audit.success("form.option_source.disable", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("status")), Map.of());
        return requireSource(sourceId);
    }

    @Transactional
    public void delete(long sourceId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        lockSourceRow(sourceId);
        Long published = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_option_data_source_version
            WHERE source_id = ? AND status = 'PUBLISHED'
            """, Long.class, sourceId);
        if (published != null && published > 0) {
            throw new BizException("OPTION_SOURCE_PUBLISHED", "已发布过的数据源不能删除，可以停用");
        }
        if (isReferenced(sourceId)) {
            throw new BizException("OPTION_SOURCE_REFERENCED", "数据源仍被表单引用，不能删除");
        }
        int deletedVersions = jdbc.update("DELETE FROM t_option_data_source_version WHERE source_id = ?", sourceId);
        jdbc.update("DELETE FROM t_option_data_source WHERE id = ?", sourceId);
        audit.success("form.option_source.delete", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("deleted")),
            Map.of("deletedDraftVersions", deletedVersions));
    }

    public List<BindableSource> bindable(long formId) {
        authorization.requireFormMaintenance(formId, PermissionCodes.FORM_DEFINITION_MANAGE);
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        List<Object> args = new ArrayList<>();
        String predicate = "";
        if (!principal.isAdmin()) {
            predicate = """
                AND EXISTS (
                  SELECT 1 FROM t_option_data_source_grant source_grant
                  WHERE source_grant.source_id = source.id AND (
                    (source_grant.subject_type = 'USER' AND source_grant.subject_id = ?)
                    OR (source_grant.subject_type = 'ROLE' AND source_grant.subject_id IN (
                      SELECT user_role.role_id FROM t_user_role user_role
                      JOIN t_role role ON role.id = user_role.role_id AND role.enabled = true
                      WHERE user_role.user_id = ?
                    ))
                  )
                )
                """;
            args.add(principal.userId());
            args.add(principal.userId());
        }
        return jdbc.query("""
            SELECT source.id, source.code, source.name,
                   version_row.id AS version_id, version_row.version_no,
                   version_row.columns_json::text, version_row.row_count,
                   latest.version_no AS latest_version_no
            FROM t_option_data_source source
            JOIN t_option_data_source_version version_row
              ON version_row.source_id = source.id AND version_row.status = 'PUBLISHED'
            JOIN LATERAL (
              SELECT MAX(version_no) AS version_no FROM t_option_data_source_version candidate
              WHERE candidate.source_id = source.id AND candidate.status = 'PUBLISHED'
            ) latest ON true
            WHERE source.status = 'ACTIVE'
            """ + predicate + " ORDER BY source.name, version_row.version_no DESC",
            (rs, row) -> new BindableSource(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getLong("version_id"), rs.getInt("version_no"),
                readColumns(rs.getString("columns_json")), rs.getInt("row_count"),
                rs.getInt("latest_version_no")), args.toArray());
    }

    public List<Map<String, Object>> previewRows(long sourceId, long versionId, int limit) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        requireSource(sourceId);
        if (version(versionId).sourceId() != sourceId) throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        return jdbc.query("""
            SELECT row_no, data::text FROM t_option_data_source_row
            WHERE version_id = ? ORDER BY row_no LIMIT ?
            """, (rs, row) -> {
                try {
                    return Map.<String, Object>of("row_no", rs.getInt(1), "data", json.readValue(rs.getString(2), Map.class));
                } catch (Exception error) { throw new IllegalStateException("invalid option row", error); }
            }, versionId, Math.min(Math.max(limit, 1), 100));
    }

    private SourceSummary requireSource(long sourceId) {
        return list().stream().filter(source -> source.id() == sourceId).findFirst()
            .orElseThrow(() -> new BizException("OPTION_SOURCE_NOT_FOUND", "数据源不存在"));
    }

    private boolean isReferenced(long sourceId) {
        Boolean referenced = jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1
              FROM (
                SELECT schema FROM t_form_definition WHERE deleted = 0
                UNION ALL
                SELECT schema FROM t_form_definition_version
              ) form_schema
              WHERE jsonb_path_exists(
                form_schema.schema,
                '$.**.props.optionSource.sourceId ? (@ == $sourceId)',
                jsonb_build_object('sourceId', to_jsonb(?::bigint))
              )
            )
            """, Boolean.class, sourceId);
        return Boolean.TRUE.equals(referenced);
    }

    private VersionView version(long versionId) {
        VersionView version = jdbc.query("""
            SELECT id, source_id, version_no, status, columns_json::text, row_count,
                   original_name, sha256, created_at, published_at
            FROM t_option_data_source_version WHERE id = ?
            """, rs -> rs.next() ? versionView(rs) : null, versionId);
        if (version == null) throw new BizException("OPTION_SOURCE_VERSION_NOT_FOUND", "数据版本不存在");
        return version;
    }

    private List<VersionView> versions(long sourceId, boolean publishedOnly) {
        return jdbc.query("""
            SELECT id, source_id, version_no, status, columns_json::text, row_count,
                   original_name, sha256, created_at, published_at
            FROM t_option_data_source_version
            WHERE source_id = ? AND (? = false OR status = 'PUBLISHED')
            ORDER BY version_no DESC
            """, (rs, row) -> versionView(rs), sourceId, publishedOnly);
    }

    private VersionView versionView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VersionView(rs.getLong("id"), rs.getLong("source_id"), rs.getInt("version_no"),
            rs.getString("status"), readColumns(rs.getString("columns_json")),
            rs.getInt("row_count"), rs.getString("original_name"), rs.getString("sha256"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("published_at", OffsetDateTime.class));
    }

    private List<String> readColumns(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("invalid option source columns", error);
        }
    }

    private void requireSubjects(Set<Long> userIds, Set<Long> roleIds) {
        for (Long id : userIds) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE id = ? AND status = 'ACTIVE'",
                Long.class, id);
            if (count == null || count == 0) throw new BizException("USER_NOT_FOUND", "授权用户不存在或已停用");
        }
        for (Long id : roleIds) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_role WHERE id = ? AND enabled = true",
                Long.class, id);
            if (count == null || count == 0) throw new BizException("ROLE_NOT_FOUND", "授权角色不存在或已停用");
        }
    }

    private ImportedTable parseFile(String name, byte[] bytes, String sheetName) throws Exception {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return parseXlsx(bytes, sheetName);
        String content = stripBom(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
        if (lower.endsWith(".csv")) return parseDelimited(content, ',');
        if (lower.endsWith(".txt")) return parsePastedText(content);
        throw new BizException("OPTION_SOURCE_TYPE_UNSUPPORTED", "仅支持 .xls、.xlsx、.csv 和 .txt（文本需为 UTF-8）");
    }

    private ImportedTable parsePastedText(String text) {
        if (text.contains("\t")) return parseDelimited(text, '\t');
        List<List<String>> rows = text.lines().map(String::trim).filter(value -> !value.isEmpty())
            .map(value -> List.of(value)).toList();
        return new ImportedTable(List.of("选项"), rows);
    }

    private ImportedTable parseDelimited(String text, char delimiter) {
        List<List<String>> rows = csvRows(text, delimiter);
        if (rows.isEmpty()) throw new BizException("OPTION_SOURCE_EMPTY", "导入内容为空");
        return new ImportedTable(rows.get(0), rows.subList(1, rows.size()));
    }

    private ImportedTable parseXlsx(byte[] bytes, String sheetName) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet;
            if (sheetName != null && !sheetName.isBlank()) {
                sheet = workbook.getSheet(sheetName.trim());
                if (sheet == null) throw new BizException("OPTION_SOURCE_SHEET_NOT_FOUND", "工作表不存在");
            } else if (workbook.getNumberOfSheets() == 1) {
                sheet = workbook.getSheetAt(0);
            } else {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) names.add(workbook.getSheetName(i));
                throw new BizException("OPTION_SOURCE_SHEET_REQUIRED", "请选择工作表：" + String.join("、", names));
            }
            DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                int width = Math.max(0, row.getLastCellNum());
                if (width > MAX_COLUMNS) throw new BizException("OPTION_SOURCE_COLUMNS_INVALID", "列数不能超过 50");
                List<String> values = new ArrayList<>();
                for (int column = 0; column < width; column++) {
                    values.add(formatter.formatCellValue(row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).trim());
                }
                if (values.stream().anyMatch(value -> !value.isBlank())) rows.add(values);
                if (rows.size() > MAX_ROWS + 1) break;
            }
            if (rows.isEmpty()) throw new BizException("OPTION_SOURCE_EMPTY", "工作表为空");
            return new ImportedTable(rows.get(0), rows.subList(1, rows.size()));
        }
    }

    private List<List<String>> csvRows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"'); index++;
                } else quoted = !quoted;
            } else if (current == delimiter && !quoted) {
                row.add(cell.toString().trim()); cell.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                row.add(cell.toString().trim()); cell.setLength(0);
                if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(current);
        }
        if (quoted) throw new BizException("OPTION_SOURCE_CSV_INVALID", "CSV 引号未闭合");
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
        return rows;
    }

    private void validateTable(ImportedTable table) {
        if (table.columns().isEmpty() || table.columns().size() > MAX_COLUMNS) {
            throw new BizException("OPTION_SOURCE_COLUMNS_INVALID", "列数必须为 1 到 50");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : table.columns()) {
            String column = raw == null ? "" : raw.trim();
            if (column.isBlank() || column.length() > 100 || !unique.add(column)) {
                throw new BizException("OPTION_SOURCE_HEADER_INVALID", "表头必须非空、唯一且不超过 100 字符");
            }
        }
        if (table.rows().isEmpty() || table.rows().size() > MAX_ROWS) {
            throw new BizException("OPTION_SOURCE_ROWS_INVALID", "数据行数必须为 1 到 20000");
        }
        for (int index = 0; index < table.rows().size(); index++) {
            List<String> row = table.rows().get(index);
            if (row.size() > table.columns().size()) {
                throw new BizException("OPTION_SOURCE_ROW_INVALID", "第 " + (index + 2) + " 行数据列数超过表头列数");
            }
            for (int column = 0; column < row.size(); column++) {
                if (row.get(column) != null && row.get(column).length() > MAX_CELL_LENGTH) {
                    throw new BizException("OPTION_SOURCE_CELL_TOO_LONG", "第 " + (index + 2) + " 行第 " + (column + 1) + " 列超过 500 字符");
                }
            }
        }
    }

    private Map<String, String> rowMap(List<String> columns, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            row.put(columns.get(index).trim(), index < values.size() ? values.get(index) : "");
        }
        return row;
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) return "import";
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String stripBom(String value) { return value.startsWith("\uFEFF") ? value.substring(1) : value; }

    public record ImportPreview(List<String> sheets, List<String> columns, int rowCount, List<Map<String, String>> rows) { }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }

    private record ImportedTable(List<String> columns, List<List<String>> rows) { }
    public record SourceWrite(String code, String name) { }
    public record GrantWrite(Integer version, Set<Long> userIds, Set<Long> roleIds) {
        public GrantWrite {
            userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
            roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        }
    }
    public record SourceSummary(long id, String code, String name, String status, int version,
                                OffsetDateTime updatedAt, Long publishedVersionId,
                                Integer publishedVersionNo, Integer rowCount, Long draftVersionId) { }
    public record SourceDetail(SourceSummary source, List<VersionView> versions,
                               List<Long> userIds, List<Long> roleIds, boolean deletable) { }
    public record VersionView(long id, long sourceId, int versionNo, String status,
                              List<String> columns, int rowCount, String originalName,
                              String sha256, OffsetDateTime createdAt, OffsetDateTime publishedAt) { }
    public record BindableSource(long id, String code, String name, long versionId,
                                 int versionNo, List<String> columns, int rowCount,
                                 int latestVersionNo) { }
}
