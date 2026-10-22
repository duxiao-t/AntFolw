package com.antflow.integration.wecom;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 企微同步任务的租约：**谁抢到 job，谁才有权写**。
 *
 * <p>为什么独立成组件：租约是横切关注点——同步主流程、成员批次、部门、负责人、直属上级、
 * 进度上报，每一步写库前都要校验。它自带两个可变状态（{@link #runningClaims} 供续租遍历、
 * {@link #currentLease} 保存当前线程持有的租约），混在 1300 行的 {@code WecomService} 里时，
 * 任何拆分都必须共享这两个字段，拆出来的类会立刻变成"共享可变状态"的反例。
 *
 * <p>两条纪律：
 * <ol>
 *   <li>**校验与续租必须走本类**，不要在业务类里自己查 {@code claim_token}——两处判断口径一旦分叉，
 *       表现是"两个实例同时写同一批数据"，且日志上看不出异常。</li>
 *   <li>{@link #currentLease} 是 **ThreadLocal**：{@link #enter}/{@link #exit} 必须成对出现在
 *       同一个线程的 try/finally 里。线程池复用时漏掉 exit，下一个任务会继承上一个 job 的租约。</li>
 * </ol>
 *
 * <p>租约期限固定 120 秒（写死在 SQL 里，与 {@code antflow.wecom.lease-renew-interval-ms}
 * 的续租频率配套）；要改期限必须同时确认续租间隔仍明显小于它。
 */
@Component
@RequiredArgsConstructor
class WecomSyncLease {

    private final JdbcTemplate jdbc;

    /** jobId → claim token。续租任务遍历它，退出时必须按 token 精确移除（避免误删后来者）。 */
    private final Map<Long, UUID> runningClaims = new ConcurrentHashMap<>();

    /** 当前线程持有的租约。只由 {@link #enter} 写入、{@link #exit} 清除。 */
    private final ThreadLocal<Lease> currentLease = new ThreadLocal<>();

    /**
     * 抢占 job。返回 token 表示抢到；返回 null 表示别人持有未过期的租约。
     *
     * <p>"PENDING" 或"RUNNING 但租约已过期/为空"都可抢——后者用于实例崩溃后的接管。
     */
    UUID claim(long jobId) {
        UUID token = UUID.randomUUID();
        int claimed = jdbc.update("""
            UPDATE t_wecom_sync_job
            SET status = 'RUNNING', claim_token = ?, lease_expires_at = now() + interval '120 seconds',
                started_at = COALESCE(started_at, now()), finished_at = NULL,
                phase = 'CONNECTING', percent = 0, total_users = 0, processed_users = 0,
                created_users = 0, updated_users = 0, failed_users = 0,
                message = '重新拉取通讯录并继续同步', error_summary = '[]'::jsonb, updated_at = now()
            WHERE id = ? AND (status = 'PENDING'
                OR (status = 'RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at <= now())))
            """, token, jobId);
        return claimed == 1 ? token : null;
    }

    /** 进入同步任务：登记给续租任务，并把租约绑到当前线程。必须与 {@link #exit} 成对。 */
    void enter(long jobId, UUID token) {
        currentLease.set(new Lease(jobId, token));
        runningClaims.put(jobId, token);
    }

    /** 退出同步任务。**按 token 精确移除**：如果本线程已失去租约、job 被别的实例接管，不能删掉对方的登记。 */
    void exit(long jobId, UUID token) {
        runningClaims.remove(jobId, token);
        currentLease.remove();
    }

    /**
     * 写库前校验：当前线程持有租约，且库里仍是同一 token、未过期、状态为 RUNNING。
     *
     * <p>用 {@code FOR UPDATE} 锁住 job 行：校验与后续写入在同一个事务里时，避免校验通过后
     * 被别的实例抢走。凡是"校验 + 写"跨两条语句的地方，都必须在同一个事务里调用它。
     *
     * @throws LeaseLostException 没有租约、或租约已被接管/过期
     */
    void require() {
        Lease lease = currentLease.get();
        if (lease == null) throw new LeaseLostException();
        Long id = jdbc.query("""
            SELECT id FROM t_wecom_sync_job
            WHERE id = ? AND claim_token = ? AND status = 'RUNNING' AND lease_expires_at > now()
            FOR UPDATE
            """, rs -> rs.next() ? rs.getLong(1) : null, lease.jobId(), lease.token());
        if (id == null) throw new LeaseLostException();
    }

    /** 当前线程持有的 token；没有租约时返回 null（{@code fail()} 用它判断还能不能落失败状态）。 */
    UUID currentToken() {
        Lease lease = currentLease.get();
        return lease == null ? null : lease.token();
    }

    /**
     * 给所有在跑的 job 续期。由 {@code WecomService.renewSyncLeases()} 按
     * {@code antflow.wecom.lease-renew-interval-ms} 周期调用。
     *
     * <p>SQL 里的 {@code lease_expires_at > now()} 是有意的：租约已经过期就不再续，
     * 让接管方拿走，而不是把一条僵尸任务续活。
     */
    void renewAll() {
        runningClaims.forEach((id, token) -> {
            jdbc.update("""
                UPDATE t_wecom_sync_job SET lease_expires_at = now() + interval '120 seconds'
                WHERE id = ? AND claim_token = ? AND status = 'RUNNING' AND lease_expires_at > now()
                """, id, token);
        });
    }

    private record Lease(long jobId, UUID token) { }

    /** 租约丢失：不是错误，是"另一个实例接手了"，上层应静默停止而不是标记失败。 */
    static class LeaseLostException extends RuntimeException { }
}
