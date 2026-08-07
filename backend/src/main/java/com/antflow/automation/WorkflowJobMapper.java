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
    WorkflowJob claimDue(@Param("workerId") String workerId);

    @Select("SELECT * FROM t_workflow_job WHERE id = #{id} FOR UPDATE")
    WorkflowJob selectForUpdate(@Param("id") Long id);

    @Select("""
        SELECT * FROM t_workflow_job
        WHERE proc_inst_id = #{instanceId} AND node_id = #{nodeId} AND job_type = #{jobType}
        """)
    WorkflowJob findNodeJob(@Param("instanceId") Long instanceId,
                            @Param("nodeId") String nodeId,
                            @Param("jobType") String jobType);

    @Update("""
        UPDATE t_workflow_job
        SET status = 'SCHEDULED', scheduled_at = now(), locked_at = NULL,
            locked_by = NULL, updated_at = now()
        WHERE status = 'RUNNING' AND locked_at < #{cutoff}
        """)
    int recoverStale(@Param("cutoff") OffsetDateTime cutoff);

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
