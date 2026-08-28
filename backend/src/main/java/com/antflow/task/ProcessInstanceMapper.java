package com.antflow.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ProcessInstanceMapper extends BaseMapper<ProcessInstance> {
    String WORKPLACE_VISIBLE = """
        FROM t_process_instance pi
        JOIN t_form_data form_data ON form_data.id = pi.form_data_id
        WHERE (#{admin}
          OR pi.started_by = #{userId}
          OR EXISTS (
            SELECT 1 FROM t_task task
            WHERE task.proc_inst_id = pi.id AND task.assignee_id = #{userId}
              AND task.status = 'PENDING'
          )
          OR (#{canReadInstances}
            AND EXISTS (
              SELECT 1 FROM t_form_resource_grant form_grant
              WHERE form_grant.form_def_id = form_data.form_def_id
                AND ((form_grant.subject_type = 'USER' AND form_grant.subject_id = #{userId})
                  OR (form_grant.subject_type = 'ROLE' AND form_grant.subject_id IN (
                    SELECT user_role.role_id FROM t_user_role user_role
                    JOIN t_role granted_role ON granted_role.id = user_role.role_id
                      AND granted_role.enabled = true
                    WHERE user_role.user_id = #{userId}
                  )))
            )
            AND EXISTS (
              SELECT 1 FROM t_user_role user_role
              JOIN t_role role ON role.id = user_role.role_id AND role.enabled = true
              JOIN t_role_permission role_permission ON role_permission.role_id = role.id
                AND role_permission.permission_code = 'workflow.instance.read'
              LEFT JOIN t_user viewer ON viewer.id = #{userId}
              WHERE user_role.user_id = #{userId}
                AND (role.data_scope = 'ALL'
                  OR (role.data_scope = 'SELF' AND pi.started_by = #{userId})
                  OR (role.data_scope = 'DEPARTMENT'
                    AND viewer.dept_id IS NOT NULL AND viewer.dept_id = pi.started_dept_id)
                  OR (role.data_scope = 'DEPARTMENT_AND_DESCENDANTS' AND EXISTS (
                    SELECT 1 FROM t_department child, t_department parent
                    WHERE child.id = pi.started_dept_id AND parent.id = viewer.dept_id
                      AND child.path <@ parent.path
                  ))
                  OR (role.data_scope = 'CUSTOM' AND EXISTS (
                    SELECT 1 FROM t_role_department role_department
                    WHERE role_department.role_id = role.id
                      AND role_department.department_id = pi.started_dept_id
                  )))
            ))
        )
        """;

    @Select("SELECT * FROM t_process_instance WHERE id = #{id} FOR UPDATE")
    ProcessInstance selectForUpdate(@Param("id") Long id);

    @Select("SELECT pi.* " + WORKPLACE_VISIBLE
        + " ORDER BY pi.started_at DESC, pi.id DESC LIMIT #{limit}")
    List<ProcessInstance> selectWorkplaceRecent(@Param("userId") long userId,
        @Param("admin") boolean admin, @Param("canReadInstances") boolean canReadInstances,
        @Param("limit") int limit);

    @Select("""
        SELECT pi.status AS status, COUNT(*) AS total,
          COUNT(*) FILTER (WHERE pi.finished_at >= #{dayStart} AND pi.finished_at < #{dayEnd})
            AS finished_today
        """ + WORKPLACE_VISIBLE + " GROUP BY pi.status")
    List<Map<String, Object>> selectWorkplaceStatusCounts(@Param("userId") long userId,
        @Param("admin") boolean admin, @Param("canReadInstances") boolean canReadInstances,
        @Param("dayStart") OffsetDateTime dayStart, @Param("dayEnd") OffsetDateTime dayEnd);
}
