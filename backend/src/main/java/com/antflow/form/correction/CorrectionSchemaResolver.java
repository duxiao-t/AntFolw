package com.antflow.form.correction;

import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 修正场景下的 schema 唯一取值口径。
 *
 * <p><b>为什么必须只有一处实现</b>：修正要"按单据填报时那一版表单"渲染与校验。
 * 如果渲染走一条路、校验走另一条，就会出现"字段按 A 版显示、值按 B 版校验"的错位——
 * 这类错误不会立刻报错，只会让某些字段莫名其妙地校验不通过，极难定位。
 *
 * <p>取值顺序：
 * <ol>
 *   <li>有修订指针 → 用修订指向的那一版 schema（与实例详情完全同源）</li>
 *   <li>没有修订指针（历史导入行只写了主表）→ 回落 {@code (form_def_id, form_def_version)}</li>
 *   <li>两者都有但版本不一致 → **fail-closed**，抛 409。不猜、不挑一个用：
 *       不一致意味着数据自相矛盾，任何一方都可能是错的。</li>
 * </ol>
 *
 * <p>刻意**不使用** {@code t_form_definition.schema}（表单最新版）：那是"重新填报"的语义，
 * 而修正要改的是这张单据自己的历史内容。当前库里 10% 的单据版本已落后于表单，
 * 用最新版会让它们直接不可用，也会让历史解释失去依据。
 */
@Component
@RequiredArgsConstructor
public class CorrectionSchemaResolver {

    /** @param schema 用于渲染与校验的 schema 文本；@param approximate 该版本是近似还原的 */
    public record Resolved(String schema, boolean approximate) {
    }

    private final JdbcTemplate jdbc;

    public Resolved resolve(long formDefId, int formDefVersion, Long currentRevisionId) {
        Snapshot revision = currentRevisionId == null ? null : revisionSnapshot(currentRevisionId);
        Snapshot declared = declaredSnapshot(formDefId, formDefVersion);

        if (revision != null && declared != null && revision.versionNo() != declared.versionNo()) {
            throw new BizException("CORRECTION_SCHEMA_CONFLICT",
                "单据的修订版本与声明版本不一致，已拒绝修正");
        }
        Snapshot chosen = revision != null ? revision : declared;
        if (chosen == null) {
            throw new BizException("CORRECTION_SCHEMA_UNAVAILABLE",
                "找不到该单据对应的表单版本，无法安全修正");
        }
        return new Resolved(chosen.schema(), chosen.approximate());
    }

    private record Snapshot(int versionNo, String schema, boolean approximate) {
    }

    private Snapshot revisionSnapshot(long revisionId) {
        return jdbc.query("""
            SELECT version.version_no, version.schema::text, version.legacy_approximate
            FROM t_form_data_revision revision
            JOIN t_form_definition_version version
              ON version.id = revision.form_definition_version_id
            WHERE revision.id = ?
            """, rs -> rs.next()
                ? new Snapshot(rs.getInt(1), rs.getString(2), rs.getBoolean(3))
                : null, revisionId);
    }

    private Snapshot declaredSnapshot(long formDefId, int versionNo) {
        return jdbc.query("""
            SELECT version_no, schema::text, legacy_approximate
            FROM t_form_definition_version
            WHERE form_definition_id = ? AND version_no = ?
            """, rs -> rs.next()
                ? new Snapshot(rs.getInt(1), rs.getString(2), rs.getBoolean(3))
                : null, formDefId, versionNo);
    }
}
