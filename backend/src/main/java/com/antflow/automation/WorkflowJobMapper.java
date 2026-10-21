package com.antflow.automation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface WorkflowJobMapper extends BaseMapper<WorkflowJob> {

    @Select("""
        WITH candidate AS (
            SELECT id
            FROM t_workflow_job
            WHERE status = 'SCHEDULED' AND scheduled_at <= now()
            ORDER BY scheduled_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        UPDATE t_workflow_job job
        SET status = 'RUNNING', attempts = attempts + 1,
            locked_at = now(), locked_by = #{workerId}, updated_at = now()
        FROM candidate
        WHERE job.id = candidate.id
        RETURNING job.*
        """)
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    WorkflowJob claimDue(@Param("workerId") String workerId);

    @Select("SELECT * FROM t_workflow_job WHERE id = #{id} FOR UPDATE")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    WorkflowJob selectForUpdate(@Param("id") Long id);

    /**
     * 同一实例、同一节点、同一轮次的 DELAY/TRIGGER 作业。
     *
     * <p>按「存活优先」取一行：RUNNING/SCHEDULED = 仍在跑或等待；SUCCEEDED = 本轮已完成；
     * CANCELLED/FAILED = 终态历史。调用方据状态决定「复用」还是「重建」——幂等键含轮次
     * 是 V60 的约束，重建/新轮次都在新行上落，历史行不再被顶掉。
     */
    @Select("""
        SELECT * FROM t_workflow_job
        WHERE proc_inst_id = #{instanceId} AND node_id = #{nodeId}
          AND job_type = #{jobType} AND round_no = #{roundNo}
        ORDER BY CASE status
                   WHEN 'RUNNING' THEN 0
                   WHEN 'SCHEDULED' THEN 1
                   WHEN 'SUCCEEDED' THEN 2
                   ELSE 3
                 END,
                 id DESC
        LIMIT 1
        """)
    WorkflowJob findNodeJob(@Param("instanceId") Long instanceId,
                            @Param("nodeId") String nodeId,
                            @Param("jobType") String jobType,
                            @Param("roundNo") int roundNo);

    @Update("""
        UPDATE t_workflow_job
        SET status = 'SCHEDULED', scheduled_at = now(), locked_at = NULL,
            locked_by = NULL, updated_at = now()
        WHERE status = 'RUNNING' AND locked_at < #{cutoff}
        """)
    int recoverStale(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 续租本 worker 手上正在投递的作业（与 outbox 的租约同一套做法）。
     *
     * <p>没有它，{@link #recoverStale} 会把"投递超过 stale-lock-timeout"的作业置回 SCHEDULED
     * 再投一遍——webhook 重复、完成动作重复。投递本身有界，但等实例行锁没有上界。
     */
    @Update("""
        UPDATE t_workflow_job
        SET locked_at = now(), updated_at = now()
        WHERE status = 'RUNNING' AND locked_by = #{workerId}
        """)
    int renewOwnedLeases(@Param("workerId") String workerId);

    @Update("""
        UPDATE t_workflow_job
        SET status = 'CANCELLED', locked_at = NULL, locked_by = NULL, updated_at = now()
        WHERE proc_inst_id = #{instanceId} AND status IN ('SCHEDULED', 'RUNNING')
        """)
    int cancelActive(@Param("instanceId") Long instanceId);

    @Update("""
        UPDATE t_workflow_job
        SET status = 'SCHEDULED', attempts = 0, scheduled_at = now(),
            last_error = NULL, locked_at = NULL, locked_by = NULL,
            completed_at = NULL, updated_at = now()
        WHERE id = #{jobId} AND proc_inst_id = #{instanceId} AND status = 'FAILED'
        """)
    int retryFailed(@Param("instanceId") Long instanceId, @Param("jobId") Long jobId);
}
