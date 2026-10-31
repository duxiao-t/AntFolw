package com.antflow.form.options;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class OptionSourceService {
    // 导入规模上限与表格解析已搬到 OptionTableImporter：这里是配置管理，不该混进 CSV 状态机。
    /** 版本对比每侧最多读多少行（2 万行的极端表也只在内存里比 5000 行，超出标记 truncated）。 */
    private static final int DIFF_SCAN_LIMIT = 5_000;
    /** 版本对比最多回多少行明细（界面两列并排，再多也没人看）。 */
    private static final int DIFF_ROW_LIMIT = 200;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authorization;
    private final AuditService audit;
    /** 引用关系只读查询（表单侧的表），见 {@link OptionBindingGuard}：配置写入与引用判定是两套数据所有权。 */
    private final OptionBindingGuard bindingGuard;
    /** 版本只读查询与行映射（F3 职责抽取，见 {@link OptionSourceVersionReader}）。 */
    private final OptionSourceVersionReader versionReader;

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
        List<VersionView> versions = versionReader.versions(sourceId, false);
        List<Long> userIds = jdbc.queryForList("""
            SELECT subject_id FROM t_option_data_source_grant
            WHERE source_id = ? AND subject_type = 'USER' ORDER BY subject_id
            """, Long.class, sourceId);
        List<Long> roleIds = jdbc.queryForList("""
            SELECT subject_id FROM t_option_data_source_grant
            WHERE source_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
            """, Long.class, sourceId);
        List<FormRef> forms = bindingGuard.referencedForms(sourceId);
        int boundForms = source.inUseFormCount();
        long publishedVersions = versions.stream().filter(v -> "PUBLISHED".equals(v.status())).count();
        // 判据必须与 delete() 一致，否则界面说不可删却能直接调 DELETE（反之亦然）。
        // 用"有没有任何已发布版本"而不是"当前可用版本"：全下架的源 publishedVersionId 为 null，
        // 但它发布过，delete() 仍然会拒。
        boolean deletable = publishedVersions == 0 && boundForms == 0 && forms.isEmpty();
        return new SourceDetail(source, versions, userIds, roleIds, forms, bindingGuard.versionUsage(sourceId),
            deletable, deletable ? null : OptionBindingGuard.deleteBlockedReason(publishedVersions, boundForms, forms.size()));
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
        bindingGuard.requireForms(request.formIds());
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

    @Transactional
    public VersionView importDraft(long sourceId, MultipartFile file, String text, String sheetName,
                                   String note) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        lockSource(sourceId);
        try {
            boolean hasFile = file != null && !file.isEmpty();
            OptionTableImporter.ImportedTable table = OptionTableImporter.read(file, text, sheetName);
            byte[] bytes = hasFile ? file.getBytes() : text.getBytes(StandardCharsets.UTF_8);
            String name = hasFile ? OptionTableImporter.safeName(file.getOriginalFilename()) : "pasted-text.txt";
            jdbc.update("DELETE FROM t_option_data_source_version WHERE source_id = ? AND status = 'DRAFT'",
                sourceId);
            Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                FROM t_option_data_source_version WHERE source_id = ?
                """, Integer.class, sourceId);
            Long versionId = jdbc.queryForObject("""
                INSERT INTO t_option_data_source_version(
                    source_id, version_no, status, columns_json, row_count,
                    original_name, sha256, created_by, note)
                VALUES (?, ?, 'DRAFT', ?::jsonb, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, sourceId, nextVersion, json.writeValueAsString(table.columns()),
                table.rows().size(), name, sha256(bytes), authorization.currentUserId(),
                normalizeNote(note));
            List<Object[]> batch = new ArrayList<>();
            for (int index = 0; index < table.rows().size(); index++) {
                batch.add(new Object[] {versionId, index + 1,
                    json.writeValueAsString(OptionTableImporter.rowMap(table.columns(), table.rows().get(index)))});
            }
            jdbc.batchUpdate("""
                INSERT INTO t_option_data_source_row(version_id, row_no, data)
                VALUES (?, ?, ?::jsonb)
                """, batch);
            // version 是给 replaceGrants/replaceForms 用的乐观锁：导入把草稿整个换掉了（连 versionId 都换），
            // 不递增的话，导入前打开的旧页面还能拿旧 version 提交授权/引用，把刚改的配置盖回去。
            // 其余写操作（publish/unpublish/disable/enable/停用版本/丢弃/两份 replace）都递增，这里不能例外。
            jdbc.update("UPDATE t_option_data_source SET version = version + 1, updated_at = now() WHERE id = ?",
                sourceId);
            audit.success("form.option_source.import", "OPTION_SOURCE", sourceId,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("draftVersion")),
                Map.of("rowCount", table.rows().size(), "columnCount", table.columns().size(),
                    "noteLength", normalizeNote(note) == null ? 0 : normalizeNote(note).length()));
            return versionReader.requireVersion(versionId);
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
                if (file.getSize() > OptionTableImporter.MAX_BYTES) throw new BizException("OPTION_SOURCE_TOO_LARGE", "导入文件不能超过 10 MB");
                String name = OptionTableImporter.safeName(file.getOriginalFilename()).toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
                    try (var workbook = WorkbookFactory.create(file.getInputStream())) {
                        for (int i = 0; i < workbook.getNumberOfSheets(); i++) sheets.add(workbook.getSheetName(i));
                    }
                    if (sheets.size() > 1 && (sheetName == null || sheetName.isBlank())) {
                        return new ImportPreview(sheets, List.of(), 0, List.of());
                    }
                }
            }
            OptionTableImporter.ImportedTable table = OptionTableImporter.read(file, text, sheetName);
            return new ImportPreview(sheets, table.columns(), table.rows().size(),
                table.rows().stream().limit(20).map(row -> OptionTableImporter.rowMap(table.columns(), row)).toList());
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException("OPTION_SOURCE_IMPORT_INVALID", "无法解析文件，请检查文件格式或是否加密");
        }
    }

    /** 变更说明：去掉首尾空白、超长截断、空串当没填（列上不存 ""）。 */
    private static String normalizeNote(String note) {
        if (note == null) return null;
        String trimmed = note.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
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
        return versionReader.requireVersion(versionId);
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
        VersionView target = versionReader.requireVersion(versionId);
        if (target.sourceId() != sourceId) {
            throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        }
        int referencingForms = bindingGuard.versionReferencingForms(versionId);
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
        // 一起清 disabled_at：退回待发布 = 回到"全新草稿"，而"草稿 + 已停用"是个自相矛盾的中间态
        // （停用/启用只允许作用在已发布版本上，见 setVersionDisabled）。不清的话，
        // 「停用 → 取消发布 → 再发布」会得到一个 status='PUBLISHED' 但 disabled_at 非空的版本——
        // 版本列表显示"已停用"、list() 认不出「最新」（它要求 disabled_at IS NULL）、
        // bindable() 也不给新绑定，用户会以为发布没生效。
        int updated = jdbc.update("""
            UPDATE t_option_data_source_version
            SET status = 'DRAFT', published_by = NULL, published_at = NULL, disabled_at = NULL
            WHERE id = ? AND source_id = ? AND status = 'PUBLISHED'
            """, versionId, sourceId);
        if (updated != 1) throw new BizException("OPTION_SOURCE_VERSION_NOT_PUBLISHED", "该版本不是已发布状态");
        jdbc.update("UPDATE t_option_data_source SET version = version + 1, updated_at = now() WHERE id = ?",
            sourceId);
        audit.success("form.option_source.unpublish", "OPTION_SOURCE", sourceId,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("publishedVersion")),
            Map.of("versionId", versionId, "versionNo", target.versionNo()));
        return versionReader.requireVersion(versionId);
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
        VersionView target = versionReader.requireVersion(versionId);
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
        return versionReader.requireVersion(versionId);
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
        VersionView target = versionReader.requireVersion(versionId);
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
        if (bindingGuard.isReferenced(sourceId)) {
            throw new BizException("OPTION_SOURCE_REFERENCED", "数据源仍被表单引用，不能删除");
        }
        // 源侧那份引用清单也算：管理员特意把它引用到某张表单，删掉等于把他的配置一起抹了。
        // 之前只有 detail() 看这份清单，delete() 不看，于是界面说不可删、接口却删得掉。
        if (!bindingGuard.referencedForms(sourceId).isEmpty()) {
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
        List<Long> boundVersions = bindingGuard.boundVersionIds(formId);
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
                versionReader.readColumns(rs.getString("columns_json")), rs.getInt("row_count"),
                rs.getInt("latest_version_no")), args.toArray());
    }

    public List<Map<String, Object>> previewRows(long sourceId, long versionId, int limit) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        requireSource(sourceId);
        if (versionReader.requireVersion(versionId).sourceId() != sourceId) throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        return jdbc.query("""
            SELECT row_no, data::text FROM t_option_data_source_row
            WHERE version_id = ? ORDER BY row_no LIMIT ?
            """, (rs, row) -> {
                try {
                    return Map.<String, Object>of("row_no", rs.getInt(1), "data", json.readValue(rs.getString(2), Map.class));
                } catch (Exception error) { throw new IllegalStateException("invalid option row", error); }
            }, versionId, Math.min(Math.max(limit, 1), 100));
    }

    /**
     * 版本对比：拿这一版跟它的**上一版**（version_no 次大）比。
     *
     * <p>为什么在服务端比：行数上限是 2 万，而 {@code previewRows} 只给 100 行——把两边整包丢给
     * 浏览器既慢又只看到开头。这里各读最多 {@link #DIFF_SCAN_LIMIT} 行做集合差，只回前
     * {@link #DIFF_ROW_LIMIT} 行明细 + 总数 + 是否被截断。
     *
     * <p>为什么按**整行**比：选项的"值列"是每个绑定各自选的（`props.optionSource.valueColumn`），
     * 数据源本身没有主键列，所以不假装按某一列比——整行内容变了就算一条差异（改标签会表现为
     * 一删一增，这是诚实的代价，换个列名就不成立）。
     */
    public VersionDiff diffVersions(long sourceId, long versionId) {
        authorization.requirePermission(PermissionCodes.FORM_OPTION_SOURCE_MANAGE);
        requireSource(sourceId);
        VersionView target = versionReader.requireVersion(versionId);
        if (target.sourceId() != sourceId) {
            throw new BizException("OPTION_SOURCE_VERSION_INVALID", "数据版本不属于此数据源");
        }
        VersionView previous = jdbc.query("""
            SELECT v.id, v.source_id, v.version_no, v.status, v.columns_json::text, v.row_count,
                   v.original_name, v.sha256, v.created_at, v.published_at, v.disabled_at,
                   v.note, publisher.display_name AS published_by_name
            FROM t_option_data_source_version v
            LEFT JOIN t_user publisher ON publisher.id = v.published_by
            WHERE v.source_id = ? AND v.version_no < ?
            ORDER BY v.version_no DESC LIMIT 1
            """, rs -> rs.next() ? versionReader.mapVersion(rs) : null, sourceId, target.versionNo());
        // 第一版没有上一版——不是错误，回一份空 diff 让界面把按钮藏掉就行。
        if (previous == null) {
            return new VersionDiff(target.versionNo(), null, List.of(), List.of(), List.of(), 0, 0, false);
        }
        List<String> beforeRows = rowKeys(previous.id());
        List<String> afterRows = rowKeys(target.id());
        Set<String> before = new LinkedHashSet<>(beforeRows), after = new LinkedHashSet<>(afterRows);
        // 集合差把重复行压成一条——刻意的：差异关心"这个选项还在不在"，不是"它出现了几次"。
        List<String> added = afterRows.stream().filter(row -> !before.contains(row)).toList();
        List<String> removed = beforeRows.stream().filter(row -> !after.contains(row)).toList();
        boolean truncated = beforeRows.size() >= DIFF_SCAN_LIMIT || afterRows.size() >= DIFF_SCAN_LIMIT;
        return new VersionDiff(target.versionNo(), previous.versionNo(),
            diffColumns(previous.columns(), target.columns()),
            added.stream().limit(DIFF_ROW_LIMIT).map(versionReader::readRow).toList(),
            removed.stream().limit(DIFF_ROW_LIMIT).map(versionReader::readRow).toList(),
            added.size(), removed.size(), truncated);
    }

    private List<String> rowKeys(long versionId) {
        return jdbc.query("SELECT data::text FROM t_option_data_source_row WHERE version_id = ? "
            + "ORDER BY row_no LIMIT " + DIFF_SCAN_LIMIT, (rs, row) -> rs.getString(1), versionId);
    }

    /** 列变化按"新增/删除/顺序"三件事说，顺序变化也算（分步列的顺序决定级联层级）。 */
    private static List<String> diffColumns(List<String> before, List<String> after) {
        List<String> notes = new ArrayList<>();
        for (String column : after) if (!before.contains(column)) notes.add("新增列：" + column);
        for (String column : before) if (!after.contains(column)) notes.add("删除列：" + column);
        if (new LinkedHashSet<>(before).equals(new LinkedHashSet<>(after)) && !before.equals(after)) {
            notes.add("列顺序变化：" + String.join(", ", before) + " → " + String.join(", ", after));
        }
        return notes;
    }

    private SourceSummary requireSource(long sourceId) {
        return list().stream().filter(source -> source.id() == sourceId).findFirst()
            .orElseThrow(() -> new BizException("OPTION_SOURCE_NOT_FOUND", "数据源不存在"));
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
                              OffsetDateTime disabledAt, String note, String publishedByName) { }
    public record BindableSource(long id, String code, String name, long versionId,
                                 int versionNo, List<String> columns, int rowCount,
                                 int latestVersionNo) { }

    /**
     * 一个版本相对它**上一版**的差异。{@code added}/{@code removed} 只给前
     * {@link #DIFF_ROW_LIMIT} 行，总数在 {@code addedTotal}/{@code removedTotal}，
     * 被 {@link #DIFF_SCAN_LIMIT} 截断时 {@code truncated} 为真。
     */
    public record VersionDiff(int versionNo, Integer previousVersionNo,
                              List<String> columnChanges,
                              List<Map<String, Object>> added, List<Map<String, Object>> removed,
                              int addedTotal, int removedTotal, boolean truncated) { }
}
