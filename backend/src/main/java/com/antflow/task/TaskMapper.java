package com.antflow.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    @Select("SELECT * FROM t_task WHERE id = #{id} FOR UPDATE")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    TaskEntity selectForUpdate(@Param("id") Long id);

    @Select("""
        <script>
        SELECT * FROM t_task
        WHERE assignee_id = #{userId}
          AND EXISTS (SELECT 1 FROM t_process_instance pi
            WHERE pi.id = t_task.proc_inst_id
          )
        <choose>
          <when test="view == 'done'">
            AND status IN ('APPROVED', 'REJECTED', 'RESUBMITTED')
          </when>
          <otherwise>
            AND status = 'PENDING'
          </otherwise>
        </choose>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<TaskEntity> selectTaskPage(@Param("userId") long userId,
                                    @Param("view") String view,
                                    @Param("status") String status,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM t_task
        WHERE assignee_id = #{userId}
          AND EXISTS (SELECT 1 FROM t_process_instance pi
            WHERE pi.id = t_task.proc_inst_id
          )
        <choose>
          <when test="view == 'done'">
            AND status IN ('APPROVED', 'REJECTED', 'RESUBMITTED')
          </when>
          <otherwise>
            AND status = 'PENDING'
          </otherwise>
        </choose>
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        </script>
        """)
    long countTaskPage(@Param("userId") long userId,
                       @Param("view") String view,
                       @Param("status") String status);

    @Select("""
        SELECT COUNT(*) FROM t_task
        WHERE assignee_id = #{userId} AND (status = 'PENDING' OR (status = 'CC' AND read_at IS NULL))
          AND EXISTS (SELECT 1 FROM t_process_instance pi
            WHERE pi.id = t_task.proc_inst_id)
        """)
    long countPendingInbox(@Param("userId") long userId);
}
