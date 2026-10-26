package com.antflow.form.correction;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 修正相关的 SQL 集中处。
 *
 * <p>为什么单开一个仓储而不是塞进 `FormDataMapper`：修正的写路径有**固定的写入顺序**
 * （锁行 → 比基准 → 主表 → 追加修订 → 实例指针 → 台账），把这几步的 SQL 放在一起，
 * 才能一眼看出顺序与各自的并发条件。拆散到各处的 mapper 里，顺序会被后来者悄悄改掉。
 */
@Repository
@RequiredArgsConstructor
public class FormDataCorrectionRepository {

    private final JdbcTemplate jdbc;

    /** 锁行读取：后续所有判断都以这一行为准，不从请求里信任任何值。 */
    public record DataRow(long id, long formDefId, int formDefVersion, String data, String status,
                          String businessNo, Long currentRevisionId, Long createdBy) {
    }

    public record InstanceRow(long id, String status, int version, Long currentFormRevisionId,
                              Long startedBy) {
    }

    /**
     * 必须带 {@code FOR UPDATE}：并发基准（baseRevisionId）要在这里读取后再比对，
     * 否则两个请求可以同时通过比对，各自追加一条修订。
     */
    public DataRow lockData(long formDataId) {
        return queryData(formDataId, true);
    }

    /** 只读路径（打开修正弹窗）用：不加锁，避免只读请求排在进行中的写事务后面。 */
    public DataRow findData(long formDataId) {
        return queryData(formDataId, false);
    }

    private DataRow queryData(long formDataId, boolean forUpdate) {
        return jdbc.query("""
            SELECT id, form_def_id, form_def_version, data::text, status, business_no,
                   current_revision_id, created_by
            FROM t_form_data WHERE id = ?""" + (forUpdate ? " FOR UPDATE" : ""), rs -> rs.next()
                ? new DataRow(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), (Long) rs.getObject(7),
                    (Long) rs.getObject(8))
                : null, formDataId);
    }

    /** 取最近一次实例（导入行没有实例）。 */
    public InstanceRow latestInstance(long formDataId) {
        return jdbc.query("""
            SELECT id, status, version, current_form_revision_id, started_by
            FROM t_process_instance WHERE form_data_id = ?
            ORDER BY id DESC LIMIT 1
            """, rs -> rs.next()
                ? new InstanceRow(rs.getLong(1), rs.getString(2), rs.getInt(3),
                    (Long) rs.getObject(4), (Long) rs.getObject(5))
                : null, formDataId);
    }

    /**
     * 主表更新 + 并发保险。
     *
     * <p>`IS NOT DISTINCT FROM` 而不是 `=`：{@code current_revision_id} 可为 NULL（导入行），
     * 用 `=` 会让 NULL 基准永远匹配不上，等于导入行一律不可修正。
     *
     * @return 影响行数；0 表示期间已被别的路径改过 → 调用方应转 409
     */
    public int updateDataCas(long formDataId, String dataJson, Long baseRevisionId) {
        return jdbc.update("""
            UPDATE t_form_data SET data = ?::jsonb, updated_at = now()
            WHERE id = ? AND current_revision_id IS NOT DISTINCT FROM ?
            """, dataJson, formDataId, baseRevisionId);
    }

    /** 实例指针 + 乐观锁：只动指针与 version，status/节点/完成时间一律不碰。 */
    public int updateInstancePointer(long instanceId, long revisionId, int expectedVersion) {
        return jdbc.update("""
            UPDATE t_process_instance
               SET current_form_revision_id = ?, version = version + 1
             WHERE id = ? AND version = ?
            """, revisionId, instanceId, expectedVersion);
    }

    /** 插入台账；返回主键。 */
    public long insertCorrection(long formDataId, String status, Long revisionId,
                                 Long fromRevisionId, Long instanceId, long actorUserId,
                                 String reasonCode, String reasonText, String fieldChangesJson,
                                 String proposedDataJson, boolean attachmentsChanged,
                                 Long companyIdAtTime, String instanceStatus, Long rollbackOf) {
        Long id = jdbc.query("""
            INSERT INTO t_form_data_correction(
                form_data_id, status, revision_id, from_revision_id, instance_id, actor_user_id,
                reason_code, reason_text, field_changes, proposed_data, attachments_changed,
                company_id_at_time, instance_status, rollback_of)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            formDataId, status, revisionId, fromRevisionId, instanceId, actorUserId,
            reasonCode, reasonText, fieldChangesJson, proposedDataJson, attachmentsChanged,
            companyIdAtTime, instanceStatus, rollbackOf);
        if (id == null) throw new IllegalStateException("correction row was not inserted");
        return id;
    }

    /** 待复核的修正生效后回填修订指针与状态。 */
    public int markApplied(long correctionId, long revisionId, long reviewerId, String note) {
        return jdbc.update("""
            UPDATE t_form_data_correction
               SET status = 'APPLIED', revision_id = ?, reviewed_by = ?, reviewed_at = now(),
                   review_note = ?, proposed_data = NULL
             WHERE id = ? AND status = 'PENDING_REVIEW'
            """, revisionId, reviewerId, note, correctionId);
    }

    public int markRejected(long correctionId, long reviewerId, String note) {
        return jdbc.update("""
            UPDATE t_form_data_correction
               SET status = 'REJECTED', reviewed_by = ?, reviewed_at = now(),
                   review_note = ?, proposed_data = NULL
             WHERE id = ? AND status = 'PENDING_REVIEW'
            """, reviewerId, note, correctionId);
    }

    /** 待复核行（含拟修正数据与并发基准），复核时据此落库。 */
    public record PendingRow(long id, long formDataId, String proposedData, String status,
                             Long fromRevisionId, long actorId) {
    }

    public PendingRow lockPending(long correctionId) {
        return jdbc.query("""
            SELECT id, form_data_id, proposed_data::text, status, from_revision_id, actor_user_id
            FROM t_form_data_correction WHERE id = ? FOR UPDATE
            """, rs -> rs.next()
                ? new PendingRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), (Long) rs.getObject(5), rs.getLong(6))
                : null, correctionId);
    }

    public List<CorrectionDtos.CorrectionView> list(long formDataId, int limit) {
        return jdbc.query("""
            SELECT correction.id, correction.status, correction.reason_code, correction.reason_text,
                   correction.field_changes::text, correction.attachments_changed,
                   COALESCE(NULLIF(actor.display_name, ''), actor.username) AS actor_name,
                   correction.created_at,
                   COALESCE(NULLIF(reviewer.display_name, ''), reviewer.username) AS reviewer_name,
                   correction.reviewed_at, correction.review_note
            FROM t_form_data_correction correction
            LEFT JOIN t_user actor ON actor.id = correction.actor_user_id
            LEFT JOIN t_user reviewer ON reviewer.id = correction.reviewed_by
            WHERE correction.form_data_id = ?
            ORDER BY correction.id DESC LIMIT ?
            """, (rs, rowNum) -> new CorrectionDtos.CorrectionView(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                readMap(rs.getString(5)), rs.getBoolean(6), rs.getString(7),
                rs.getObject(8, java.time.OffsetDateTime.class), rs.getString(9),
                rs.getObject(10, java.time.OffsetDateTime.class), rs.getString(11)),
            formDataId, limit);
    }

    /** 台账列表的「已修正」徽标：一次查询批量取，避免 N+1。 */
    public Set<Long> correctedFormDataIds(Collection<Long> formDataIds) {
        if (formDataIds == null || formDataIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(formDataIds.size(), "?"));
        return new HashSet<>(jdbc.queryForList(
            "SELECT DISTINCT form_data_id FROM t_form_data_correction WHERE form_data_id IN ("
                + placeholders + ") AND status = 'APPLIED'",
            Long.class, formDataIds.toArray()));
    }

    /** 最后一位审批人：修正通知要同时告知提交人与他。 */
    public Long lastApprover(long instanceId) {
        // 按 approved_at 排序（t_task 没有 completed_at，这里是实测修正过的坑：
        // 列名写错时编译不会报错，只有真跑一次才暴露）。
        List<Long> ids = jdbc.queryForList("""
            SELECT COALESCE(approved_by, assignee_id) FROM t_task
            WHERE proc_inst_id = ? AND approved_by IS NOT NULL
            ORDER BY COALESCE(approved_at, created_at) DESC, id DESC LIMIT 1
            """, Long.class, instanceId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
