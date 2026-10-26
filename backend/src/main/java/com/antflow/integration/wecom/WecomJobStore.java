package com.antflow.integration.wecom;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomService.JobDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 同步任务的持久化：创建、进度上报、状态收尾、查询。
 *
 * <p>为什么独立：{@code t_wecom_sync_job} 是这张表的唯一归属。之前它散在
 * {@code WecomService} 里——创建在 {@code start}、进度在 {@code stage/progress}、
 * 收尾在 {@code finish/fail}、查询在 {@code job/latestJob/activeJob}，改一个字段要翻四处。
 *
 * <p>三条约束：
 * <ol>
 *   <li>**进度写入必须持租约**（{@link #stage}/{@link #progress}/{@link #finish}）：被接管的旧实例
 *       不能再改任务状态，否则新实例看到的进度会被回退。</li>
 *   <li>**{@link #fail} 不校验租约、只校验 token**：失败路径本身可能就是因为租约丢了，
 *       再调 {@code require()} 会抛异常盖掉真实错误。</li>
 *   <li>**错误摘要入库前截断**（{@link #errorJson}）：10 条 × 160 字，避免 {@code error_summary}
 *       被单次同步撑爆。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class WecomJobStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WecomSyncLease lease;
    private final AuditService audit;
    private final ObjectMapper json;

    /** 错误摘要最多留几条：太多会让 job 详情页刷屏，也会把 error_summary 撑大。 */
    private static final int MAX_ERROR_SUMMARIES = 10;

    /**
     * 建一条排队中的同步任务。
     *
     * <p>{@code ON CONFLICT ... DO NOTHING} 是幂等闸门：同一企业已有 PENDING/RUNNING 时不新建。
     *
     * @return 新任务 id；已有在跑的任务时返回 null（调用方应回退到"当前活动任务"）
     */
    Long create(long companyId, String mode, long actorId) {
        return jdbc.query("""
            INSERT INTO t_wecom_sync_job(company_id, initiated_by, sync_mode, message)
            VALUES (?, ?, ?, '任务已排队')
            ON CONFLICT (company_id) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
            RETURNING id
            """, statement -> {
                statement.setLong(1, companyId);
                statement.setLong(2, actorId);
                statement.setString(3, mode);
            }, result -> result.next() ? result.getLong(1) : null);
    }

    void stage(long jobId, String phase, int percent, String message) {
        transactions.executeWithoutResult(status -> {
            lease.require();
            jdbc.update("""
            UPDATE t_wecom_sync_job SET status = 'RUNNING', phase = ?, percent = ?, message = ?,
                started_at = COALESCE(started_at, now()), updated_at = now()
            WHERE id = ? AND status IN ('PENDING', 'RUNNING')
            """, phase, percent, message, jobId);
        });
    }

    void progress(long jobId, int percent, int total, int processed, int created,
                          int updated, int failed, String message, List<String> errors) {
        transactions.executeWithoutResult(status -> {
            lease.require();
            jdbc.update("""
            UPDATE t_wecom_sync_job SET phase = 'USERS', percent = ?, total_users = ?,
                processed_users = ?, created_users = ?, updated_users = ?, failed_users = ?,
                message = ?, error_summary = CAST(? AS jsonb), updated_at = now()
            WHERE id = ?
            """, percent, total, processed, created, updated, failed, message, errorJson(errors), jobId);
        });
    }

    void finish(long jobId, String status, String message, List<String> errors) {
        transactions.executeWithoutResult(transaction -> {
            lease.require();
            jdbc.update("""
            UPDATE t_wecom_sync_job SET status = ?, phase = 'COMPLETED', percent = 100,
                message = ?, error_summary = CAST(? AS jsonb), finished_at = now(), updated_at = now(),
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = ?
            """, status, message, errorJson(errors), jobId);
        });
    }

    boolean fail(long jobId, String message, List<String> errors) {
        UUID token = lease.currentToken();
        if (token == null) return false;
        return jdbc.update("""
            UPDATE t_wecom_sync_job SET status = 'FAILED', phase = 'COMPLETED',
                message = ?, error_summary = CAST(? AS jsonb), finished_at = now(), updated_at = now(),
                claim_token = NULL, lease_expires_at = NULL
            WHERE id = ? AND claim_token = ? AND status = 'RUNNING' AND lease_expires_at > now()
            """, message, errorJson(errors), jobId, token) == 1;
    }

    /** 最近一次同步任务（单公司：不再按公司过滤）。 */
    JobDto latestJob() {
        return jdbc.query("""
            SELECT * FROM t_wecom_sync_job
            ORDER BY created_at DESC, id DESC LIMIT 1
            """, rs -> rs.next() ? mapJob(rs) : null);
    }

    /** 正在进行的同步任务（单公司：全局最多一个）。 */
    JobDto activeJob() {
        JobDto result = jdbc.query("""
            SELECT * FROM t_wecom_sync_job
            WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY created_at DESC, id DESC LIMIT 1
            """, rs -> rs.next() ? mapJob(rs) : null);
        if (result == null) throw new BizException("WECOM_JOB_CONFLICT", "同步任务状态已变化，请重试");
        return result;
    }

    JobDto job(long id, boolean failIfMissing) {
        JobDto result = jdbc.query("SELECT * FROM t_wecom_sync_job WHERE id = ?",
            rs -> rs.next() ? mapJob(rs) : null, id);
        if (result == null && failIfMissing) {
            throw new com.antflow.authz.HiddenResourceException("sync job not found");
        }
        return result;
    }

    private JobDto mapJob(ResultSet rs) throws SQLException {
        String syncMode = "FULL";
        try {
            syncMode = rs.getString("sync_mode");
        } catch (SQLException ignored) {
            // Older database rows without the column default to FULL.
        }
        return new JobDto(rs.getLong("id"), rs.getString("status"),
            rs.getString("phase"), rs.getInt("percent"), rs.getInt("total_users"),
            rs.getInt("processed_users"), rs.getInt("created_users"),
            rs.getInt("updated_users"), rs.getInt("failed_users"), rs.getString("message"),
            errorList(rs.getString("error_summary")), rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class), syncMode);
    }

    private String errorJson(List<String> errors) {
        try {
            return json.writeValueAsString(errors.stream().limit(MAX_ERROR_SUMMARIES).toList());
        } catch (Exception exception) {
            return "[]";
        }
    }

    private List<String> errorList(String value) {
        try {
            return json.readValue(value == null ? "[]" : value, new TypeReference<>() { });
        } catch (Exception exception) {
            return List.of();
        }
    }
    void auditResult(long jobId, PrincipalHolder.Principal actor) {
        try {
            JobDto result = job(jobId, false);
            audit.successAs(actor, "integration.wecom.sync.finish", "WECOM_SYNC_JOB", jobId,
                AuditService.RiskLevel.HIGH, Map.of("status", result.status()),
                Map.of("totalUsers", result.totalUsers(),
                    "createdUsers", result.createdUsers(), "updatedUsers", result.updatedUsers(),
                    "failedUsers", result.failedUsers()));
        } catch (RuntimeException ignored) {
            // The persisted job result remains authoritative if audit storage is unavailable.
        }
    }
}
