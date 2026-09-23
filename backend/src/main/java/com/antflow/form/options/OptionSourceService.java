package com.antflow.form.options;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
                   draft.id AS draft_version_id,
                   -- 区分「从没发布过」和「发布过但都下架了」，前者显示未发布、后者显示无可用版本。
                   EXISTS (SELECT 1 FROM t_option_data_source_version any_published
                           WHERE any_published.source_id = source.id
                             AND any_published.status = 'PUBLISHED') AS any_published,
                   used.in_use_count
            FROM t_option_data_source source
            -- 「最新」= 最高的**可用**（已发布且未停用）版本。全部下架时回落成 null。
            LEFT JOIN LATERAL (
              SELECT * FROM t_option_data_source_version version_row
              WHERE version_row.source_id = source.id AND version_row.status = 'PUBLISHED'
                AND version_row.disabled_at IS NULL
              ORDER BY version_row.version_no DESC LIMIT 1
            ) published ON true
            LEFT JOIN t_option_data_source_version draft
              ON draft.source_id = source.id AND draft.status = 'DRAFT'
            -- 「在用」= schema 里真的绑着这个源的字段所属表单数，和可在源侧撤销的引用清单
            -- （t_form_option_source）不是一回事。这里每个源扫一遍所有表单 schema；源/表单数量
            -- 上千时再考虑改成物化计数。
            LEFT JOIN LATERAL (
              SELECT COUNT(DISTINCT bound.form_def_id) AS in_use_count
              FROM (
                SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
                UNION ALL
                SELECT form_definition_id AS form_def_id, schema FROM t_form_definition_version
              ) bound
              WHERE jsonb_path_exists(
                bound.schema, '$.**.props.optionSource.sourceId ? (@ == $sourceId)',
                jsonb_build_object('sourceId', to_jsonb(source.id)))
            ) used ON true
            ORDER BY source.updated_at DESC, source.id DESC
            """, (rs, row) -> new SourceSummary(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("status"), rs.getInt("version"),
            rs.getObject("updated_at", OffsetDateTime.class),
            nullableLong(rs, "published_version_id"), nullableInt(rs, "published_version_no"),
            nullableInt(rs, "row_count"), nullableLong(rs, "draft_version_id"),
            rs.getBoolean("any_published"), rs.getInt("in_use_count")));
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
        List<FormRef> forms = referencedForms(sourceId);
        int boundForms = source.inUseFormCount();
        long publishedVersions = versions.stream().filter(v -> "PUBLISHED".equals(v.status())).count();
        boolean deletable = source.publishedVersionId() == null && boundForms == 0 && forms.isEmpty();
        return new SourceDetail(source, versions, userIds, roleIds, forms, versionUsage(sourceId),
            deletable, deletable ? null : deleteBlockedReason(publishedVersions, boundForms, forms.size()));
    }

    /**
     * 每个版本分别被哪些表单的字段绑着。表单在**发布那一刻**把 versionId 钉进快照，之后不重新
     * 发布那张表单就一直用旧版——所以同一个源的各个版本，在用表单可以是完全不同的两批。
     */
    private List<VersionUsage> versionUsage(long sourceId) {
        Map<Long, List<FormRef>> byVersion = new LinkedHashMap<>();
        jdbc.query("""
            WITH binding AS (
              SELECT DISTINCT form.id AS form_id, form.code AS form_code, form.name AS form_name,
                     (opt->>'versionId')::bigint AS version_id
              FROM (
                SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
                UNION ALL
                SELECT form_definition_id, schema FROM t_form_definition_version
              ) s
              JOIN t_form_definition form ON form.id = s.form_def_id
              CROSS JOIN LATERAL jsonb_path_query(s.schema, '$.**.props.optionSource') opt
              WHERE opt->>'sourceId' ~ '^[0-9]+$' AND opt->>'versionId' ~ '^[0-9]+$'
                AND (opt->>'sourceId')::bigint = ?
            )
            SELECT version_id, form_id, form_code, form_name FROM binding
            ORDER BY form_name, form_id
            """, rs -> {
            long versionId = rs.getLong("version_id");
            byVersion.computeIfAbsent(versionId, key -> new ArrayList<>())
                .add(new FormRef(rs.getLong("form_id"), rs.getString("form_code"),
                    rs.getString("form_name")));
        }, sourceId);
        return byVersion.entrySet().stream()
            .map(entry -> new VersionUsage(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * 删不掉的原因，按 {@link #delete} 里闸门的先后顺序给。界面要能把这句话显示出来——
     * 今天不可删就整个不显示「删除」，用户会以为没这个功能。
     */
    private static String deleteBlockedReason(long publishedVersions, int boundForms, int referencedForms) {
        if (publishedVersions > 0) {
            return "已发布过 " + publishedVersions + " 个版本，只能停用";
        }
        if (boundForms > 0) {
            // 用「绑定」而不是「引用」：这是 schema 里真的绑着，和下面那份可撤销的引用清单不是一回事。
            return "已被 " + boundForms + " 张表单的字段绑定，不能删除";
        }
        return "已被 " + referencedForms + " 张表单引用，不能删除";
    }

    /** 引用了这个数据源的表单。反向读 V46 的引用表。 */
    private List<FormRef> referencedForms(long sourceId) {
        return jdbc.query("""
            SELECT form.id, form.code, form.name
            FROM t_form_option_source ref
            JOIN t_form_definition form ON form.id = ref.form_def_id
            WHERE ref.source_id = ? AND form.deleted = 0
            ORDER BY form.name, form.id
            """, (rs, row) -> new FormRef(rs.getLong("id"), rs.getString("code"),
            rs.getString("name")), sourceId);
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

    /**
     * 这个数据源可以被哪些表单引用。设计器里的字段下拉只列引用过的源，所以这一步决定了
     * 哪些表单能挑到它。只改「可被选中」的清单，不授予任何数据读取——发布与运行时仍按
     * OptionRuntimeService 的授权判断。
     */
    @Transactional
    public SourceDetail replaceForms(long sourceId, FormRefWrite request) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        if (request == null || request.version() == null) {
            throw new BizException("OPTION_SOURCE_VERSION_REQUIRED", "数据源版本不能为空");
        }
        requireForms(request.formIds());
        int updated = jdbc.update("""
            UPDATE t_option_data_source SET version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, sourceId, request.version());
        if (updated != 1) throw new BizException("OPTION_SOURCE_VERSION_CONFLICT", "数据源已被其他人修改");
        jdbc.update("DELETE FROM t_form_option_source WHERE source_id = ?", sourceId);
        long actor = authorization.currentUserId();
        request.formIds().forEach(id -> jdbc.update("""
            INSERT INTO t_form_option_source(form_def_id, source_id, created_by)
            VALUES (?, ?, ?)
            """, id, sourceId, actor));
        audit.success("form.option_source.forms.update", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("formIds")),
            Map.of("formCount", request.formIds().size()));
        return detail(sourceId);
    }

    private void requireForms(Set<Long> formIds) {
        formIds.forEach(id -> {
            Long found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_form_definition WHERE id = ? AND deleted = 0
                """, Long.class, id);
            if (found == null || found == 0) {
                throw new BizException("OPTION_SOURCE_FORM_INVALID", "表单不存在：" + id);
            }
        });
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

    /**
     * 停用的反面。停用只是「暂停新绑定」——已发布表单的填报与读取一直不受影响
     * （填报值校验走 {@code requireVersion(source, false)}，不要求 ACTIVE），所以启用只是
     * 让引用过它的表单重新在设计器候选里看到它，不需要动任何表单。
     */
    @Transactional
    public SourceSummary enable(long sourceId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        int updated = jdbc.update("""
            UPDATE t_option_data_source
            SET status = 'ACTIVE', version = version + 1, updated_at = now()
            WHERE id = ? AND status = 'DISABLED'
            """, sourceId);
        if (updated == 0) requireSource(sourceId);
        audit.success("form.option_source.enable", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("status")), Map.of());
        return requireSource(sourceId);
    }

    /**
     * 取消发布：已发布版本退回「待发布」。数据都还在，之后可以重新导入覆盖它、或者丢弃它。
     * 这样「发布错了」才有退路，不用靠放宽删除来解决。
     *
     * <p>唯一的硬门槛是**没有任何表单版本快照引用这个版本**——一退，那些表单打开下拉会立刻
     * 报 422、字段提交不了。表单版本快照只增不删，所以被引用过的版本永远退不回来。
     */
    @Transactional
    public VersionView unpublish(long sourceId, long versionId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        // 用 lockSourceRow 而不是 lockSource：收回是「不再用」的动作，停用的源也该能收回它的版本。
        lockSourceRow(sourceId);
        VersionView target = version(versionId);
        if (target.sourceId() != sourceId) {
            throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        }
        int referencingForms = versionReferencingForms(versionId);
        if (referencingForms > 0) {
            throw new BizException("OPTION_SOURCE_VERSION_IN_USE",
                "有 " + referencingForms + " 张表单的已发布版本在用 v" + target.versionNo()
                    + "，不能取消发布；要停止新表单使用它，请改为停用该版本");
        }
        Long drafts = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_option_data_source_version
            WHERE source_id = ? AND status = 'DRAFT' AND id <> ?
            """, Long.class, sourceId, versionId);
        if (drafts != null && drafts > 0) {
            throw new BizException("OPTION_SOURCE_DRAFT_EXISTS", "已有待发布版本，请先发布或丢弃它");
        }
        int updated = jdbc.update("""
            UPDATE t_option_data_source_version
            SET status = 'DRAFT', published_by = NULL, published_at = NULL
            WHERE id = ? AND source_id = ? AND status = 'PUBLISHED'
            """, versionId, sourceId);
        if (updated != 1) throw new BizException("OPTION_SOURCE_VERSION_NOT_PUBLISHED", "该版本不是已发布状态");
        jdbc.update("UPDATE t_option_data_source SET version = version + 1, updated_at = now() WHERE id = ?",
            sourceId);
        audit.success("form.option_source.unpublish", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("publishedVersion")),
            Map.of("versionId", versionId, "versionNo", target.versionNo()));
        return version(versionId);
    }

    /**
     * 版本停用（下架）：从「新绑定」的候选里移除，但**状态保持 PUBLISHED**。
     *
     * <p>这就是「停用」和「取消发布」的分界：取消发布会把版本退回待发布，钉着它的表单读候选
     * 立刻 422；停用只收窄候选，读路径完全不受影响——所以**没有任何门槛，随时可做**。
     * 想先把有问题的版本止血、又不能让在用的表单坏掉，用这个。
     */
    @Transactional
    public VersionView disableVersion(long sourceId, long versionId) {
        return setVersionDisabled(sourceId, versionId, true);
    }

    /** 版本启用的反面。 */
    @Transactional
    public VersionView enableVersion(long sourceId, long versionId) {
        return setVersionDisabled(sourceId, versionId, false);
    }

    private VersionView setVersionDisabled(long sourceId, long versionId, boolean disabled) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        // 收回动作：和取消发布同一个方向，不要求源处于 ACTIVE。
        lockSourceRow(sourceId);
        VersionView target = version(versionId);
        if (target.sourceId() != sourceId) {
            throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        }
        if (!"PUBLISHED".equals(target.status())) {
            throw new BizException("OPTION_SOURCE_VERSION_NOT_PUBLISHED",
                "只有已发布的版本能停用或启用");
        }
        int updated = jdbc.update(disabled
            ? "UPDATE t_option_data_source_version SET disabled_at = now() WHERE id = ? AND disabled_at IS NULL"
            : "UPDATE t_option_data_source_version SET disabled_at = NULL WHERE id = ? AND disabled_at IS NOT NULL",
            versionId);
        if (updated == 1) {
            jdbc.update(
                "UPDATE t_option_data_source SET version = version + 1, updated_at = now() WHERE id = ?",
                sourceId);
        }
        audit.success(disabled ? "form.option_source.version.disable"
                : "form.option_source.version.enable", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("versionDisabled")),
            Map.of("versionId", versionId, "versionNo", target.versionNo()));
        return version(versionId);
    }

    /**
     * 丢弃待发布版本（连同行数据）。绝对安全：读写都要求版本是 PUBLISHED
     * （{@code requireVersion}），所以没有任何表单能绑定草稿；行数据靠 FK 级联删除。
     * 这是「导入错了、还没发布也想重来」的出口。
     */
    @Transactional
    public void discardVersion(long sourceId, long versionId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        lockSourceRow(sourceId);
        VersionView target = version(versionId);
        if (target.sourceId() != sourceId) {
            throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        }
        if (!"DRAFT".equals(target.status())) {
            throw new BizException("OPTION_SOURCE_VERSION_PUBLISHED",
                "已发布的版本不能丢弃，请先取消发布");
        }
        jdbc.update("DELETE FROM t_option_data_source_version WHERE id = ? AND source_id = ?",
            versionId, sourceId);
        jdbc.update("UPDATE t_option_data_source SET version = version + 1, updated_at = now() WHERE id = ?",
            sourceId);
        audit.success("form.option_source.version.discard", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("draftVersion")),
            Map.of("versionId", versionId, "versionNo", target.versionNo(), "rowCount", target.rowCount()));
    }

    /** 有几张表单（草稿或已发布快照）绑定了这个具体版本。 */
    private int versionReferencingForms(long versionId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT form_def_id) FROM (
              SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
              UNION ALL
              SELECT form_definition_id, schema FROM t_form_definition_version
            ) form_schema
            WHERE jsonb_path_exists(
              form_schema.schema,
              '$.**.props.optionSource ? (@.versionId == $versionId)',
              jsonb_build_object('versionId', to_jsonb(?::bigint))
            )
            """, Integer.class, versionId);
        return count == null ? 0 : count;
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
        // 这张表单现在钉着的版本：被停用的版本不进候选，**除非**正被它钉着，
        // 否则版本下拉会显示空白，管理员会以为绑定丢了。
        List<Long> boundVersions = boundVersionIds(formId);
        String boundFilter = boundVersions.isEmpty() ? ""
            : " OR version_row.id IN (" + boundVersions.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
        List<Object> args = new ArrayList<>();
        args.add(formId);
        args.add(formId);
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
              -- 已停用的版本不进候选，除非这张表单正钉着它（boundFilter 里是那些 id）。
              AND (version_row.disabled_at IS NULL""" + boundFilter + """
              )
            JOIN LATERAL (
              SELECT MAX(version_no) AS version_no FROM t_option_data_source_version candidate
              WHERE candidate.source_id = source.id AND candidate.status = 'PUBLISHED'
                AND candidate.disabled_at IS NULL
            ) latest ON true
            WHERE source.status = 'ACTIVE'
              AND (
                EXISTS (SELECT 1 FROM t_form_option_source form_ref
                        WHERE form_ref.source_id = source.id AND form_ref.form_def_id = ?)
                -- 已绑定但引用被撤掉的源也要留着：否则该字段的版本下拉会空掉、
                -- 保存值/显示名称的下拉会没选项。
                OR EXISTS (SELECT 1 FROM t_form_definition form_def
                           WHERE form_def.id = ? AND jsonb_path_exists(
                             form_def.schema, '$.**.props.optionSource.sourceId ? (@ == $sourceId)',
                             jsonb_build_object('sourceId', to_jsonb(source.id))))
              )
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
        return bindingFormCount(sourceId) > 0;
    }

    /**
     * 有几张表单在 schema 里真的绑着这个数据源（草稿 + 所有已发布版本快照，含已软删表单的快照）。
     *
     * <p>注意这和 {@code t_form_option_source}（V46 那份可在源侧撤销的引用清单）是两件事：
     * 撤引用**不会**解绑字段。删除判定只认这个数。
     */
    private int bindingFormCount(long sourceId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT form_def_id) FROM (
              SELECT id AS form_def_id, schema FROM t_form_definition WHERE deleted = 0
              UNION ALL
              SELECT form_definition_id, schema FROM t_form_definition_version
            ) form_schema
            WHERE jsonb_path_exists(
              form_schema.schema,
              '$.**.props.optionSource.sourceId ? (@ == $sourceId)',
              jsonb_build_object('sourceId', to_jsonb(?::bigint))
            )
            """, Integer.class, sourceId);
        return count == null ? 0 : count;
    }

    /**
     * 这张表单的草稿 schema 里钉着的所有 optionSource.versionId。
     *
     * <p>用途只有一个：让被停用的版本在「这张表单正用着它」时仍然留在候选里。设计器编辑的是
     * 草稿，所以只扫草稿就够。
     */
    private List<Long> boundVersionIds(long formId) {
        String schema = jdbc.query("SELECT schema::text FROM t_form_definition WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null, formId);
        if (schema == null) return List.of();
        try {
            List<Long> ids = new ArrayList<>();
            collectVersionIds(json.readTree(schema), ids);
            return ids;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 只认 props.optionSource 里的 versionId——schema 里别处也可能有同名字段。 */
    private static void collectVersionIds(JsonNode node, List<Long> target) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectVersionIds(child, target));
            return;
        }
        if (!node.isObject()) return;
        JsonNode optionSource = node.path("optionSource");
        if (optionSource.isObject()) {
            JsonNode versionId = optionSource.path("versionId");
            if (versionId.isIntegralNumber() && versionId.asLong() > 0) target.add(versionId.asLong());
        }
        node.fields().forEachRemaining(entry -> collectVersionIds(entry.getValue(), target));
    }

    private VersionView version(long versionId) {
        VersionView version = jdbc.query("""
            SELECT id, source_id, version_no, status, columns_json::text, row_count,
                   original_name, sha256, created_at, published_at, disabled_at
            FROM t_option_data_source_version WHERE id = ?
            """, rs -> rs.next() ? versionView(rs) : null, versionId);
        if (version == null) throw new BizException("OPTION_SOURCE_VERSION_NOT_FOUND", "数据版本不存在");
        return version;
    }

    private List<VersionView> versions(long sourceId, boolean publishedOnly) {
        return jdbc.query("""
            SELECT id, source_id, version_no, status, columns_json::text, row_count,
                   original_name, sha256, created_at, published_at, disabled_at
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
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getObject("disabled_at", OffsetDateTime.class));
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
                                Integer publishedVersionNo, Integer rowCount, Long draftVersionId,
                                boolean anyPublishedVersion, int inUseFormCount) { }
    public record SourceDetail(SourceSummary source, List<VersionView> versions,
                               List<Long> userIds, List<Long> roleIds, List<FormRef> forms,
                               List<VersionUsage> versionUsage, boolean deletable,
                               String deleteBlockedReason) { }
    public record FormRef(long id, String code, String name) { }
    /** 一个版本被哪些表单的字段绑着。没出现在列表里的版本就是没人在用。 */
    public record VersionUsage(long versionId, List<FormRef> forms) { }
    public record FormRefWrite(Integer version, Set<Long> formIds) {
        public FormRefWrite {
            formIds = formIds == null ? Set.of() : Set.copyOf(formIds);
        }
    }
    public record VersionView(long id, long sourceId, int versionNo, String status,
                              List<String> columns, int rowCount, String originalName,
                              String sha256, OffsetDateTime createdAt, OffsetDateTime publishedAt,
                              OffsetDateTime disabledAt) { }
    public record BindableSource(long id, String code, String name, long versionId,
                                 int versionNo, List<String> columns, int rowCount,
                                 int latestVersionNo) { }
}
