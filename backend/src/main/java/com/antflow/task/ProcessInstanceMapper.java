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
    String INSTANCE_FROM = """
        FROM t_process_instance pi
        JOIN t_form_data form_data ON form_data.id = pi.form_data_id
        JOIN t_form_definition form_def ON form_def.id = form_data.form_def_id
        """;

    String FULL_VISIBLE = """
        (#{admin}
          OR pi.started_by = #{userId}
          OR (#{canReadTasks} AND (
            EXISTS (
              SELECT 1 FROM t_task task
              WHERE task.proc_inst_id = pi.id
                AND ((task.assignee_id = #{userId} AND task.status IN ('PENDING', 'CC'))
                  OR (task.approved_by = #{userId}
                    AND task.status IN ('APPROVED', 'REJECTED')))
            )
            OR EXISTS (
              SELECT 1 FROM t_cc_record cc
              WHERE cc.proc_inst_id = pi.id AND cc.recipient_id = #{userId}
            )
          ))
          OR (#{canReadInstances}
            AND EXISTS (
              SELECT 1 FROM t_user_role user_role
              JOIN t_role role ON role.id = user_role.role_id AND role.enabled = true
              JOIN t_role_permission role_permission ON role_permission.role_id = role.id
                AND role_permission.permission_code = 'workflow:instance:read'
              JOIN t_permission permission ON permission.code = role_permission.permission_code
                AND permission.deprecated_at IS NULL
              LEFT JOIN t_user viewer ON viewer.id = #{userId}
              WHERE user_role.user_id = #{userId}
                AND (COALESCE(role_permission.scope_override, permission.default_scope, 'ALL') = 'ALL'
                  OR (COALESCE(role_permission.scope_override, permission.default_scope, 'ALL') = 'SELF'
                    AND pi.started_by = #{userId})
                  OR (COALESCE(role_permission.scope_override, permission.default_scope, 'ALL') = 'DEPARTMENT'
                    AND viewer.dept_id IS NOT NULL AND viewer.dept_id = pi.started_dept_id)
                  OR (COALESCE(role_permission.scope_override, permission.default_scope, 'ALL')
                      = 'DEPARTMENT_AND_DESCENDANTS' AND EXISTS (
                    SELECT 1 FROM t_department child, t_department parent
                    WHERE child.id = pi.started_dept_id AND parent.id = viewer.dept_id
                      AND parent.path @> child.path
                  ))
                  OR (COALESCE(role_permission.scope_override, permission.default_scope, 'ALL') = 'CUSTOM'
                    AND EXISTS (
                    SELECT 1 FROM t_role_permission_department scope_department
                    WHERE scope_department.role_id = role.id
                      AND scope_department.permission_code = role_permission.permission_code
                      AND scope_department.department_id = pi.started_dept_id
                  )))
            ))
        )
        """;

    @Select("SELECT * FROM t_process_instance WHERE id = #{id} FOR UPDATE")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "true")
    ProcessInstance selectForUpdate(@Param("id") Long id);

    @Select("""
        SELECT pi.status AS status, COUNT(*) AS total,
          COUNT(*) FILTER (WHERE pi.finished_at >= #{dayStart} AND pi.finished_at < #{dayEnd})
            AS finished_today
        """ + INSTANCE_FROM + " WHERE " + FULL_VISIBLE + " GROUP BY pi.status")
    List<Map<String, Object>> selectWorkplaceStatusCounts(@Param("userId") long userId,
        @Param("admin") boolean admin, @Param("canReadTasks") boolean canReadTasks,
        @Param("canReadInstances") boolean canReadInstances,
        @Param("dayStart") OffsetDateTime dayStart, @Param("dayEnd") OffsetDateTime dayEnd);

    String INSTANCE_PAGE_FROM = INSTANCE_FROM + """
        LEFT JOIN t_user applicant ON applicant.id = pi.started_by
        LEFT JOIN t_department applicant_department ON applicant_department.id = pi.started_dept_id
        """;

    // 列表与 count 共用筛选，确保授权、关键词和时间范围先于分页生效。
    String INSTANCE_PAGE_WHERE = """
        WHERE
        <choose>
          <when test="scope == 'mine'">
            pi.started_by = #{userId}
            <!-- 「驳回待改」的单据 current_node_id 就是 '__rework__'，平时列表要把它隐掉（另有入口），
                 但按 status=REWORK 筛时不能隐，否则那条筛选恒为空——发起人永远看不到自己待修改的单据。 -->
            <if test="status != 'REWORK'">
              AND pi.current_node_id IS DISTINCT FROM '__rework__'
            </if>
          </when>
          <otherwise>
        """ + FULL_VISIBLE + """
          </otherwise>
        </choose>
        <if test="status != null and status != ''">
          <choose>
            <when test="status == 'REWORK'">
              AND pi.status = 'RUNNING' AND pi.current_node_id = '__rework__'
            </when>
            <otherwise> AND pi.status = #{status} </otherwise>
          </choose>
        </if>
        <if test="startedBy != null">
          AND pi.started_by = #{startedBy}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (form_def.name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR form_def.code ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR form_data.business_no ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR applicant.display_name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR applicant.username ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR applicant.employee_no ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR applicant_department.name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR pi.current_node_id ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR CAST(pi.id AS text) = LTRIM(#{keyword}, '#'))
        </if>
        <if test="from != null">
          AND pi.started_at &gt;= #{from}
        </if>
        <if test="to != null">
          AND pi.started_at &lt; #{to}
        </if>
        """;

    @Select("""
        <script>
        SELECT pi.*, form_def.code AS form_code, form_def.name AS form_name,
               form_data.business_no,
               COALESCE(NULLIF(applicant.display_name, ''), applicant.username) AS applicant_name,
               applicant.employee_no AS applicant_employee_no,
               applicant_department.name AS applicant_department
        """ + INSTANCE_PAGE_FROM + INSTANCE_PAGE_WHERE + """
        ORDER BY pi.started_at DESC, pi.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<ProcessInstance> selectInstancePage(@Param("userId") long userId,
        @Param("admin") boolean admin, @Param("canReadTasks") boolean canReadTasks,
        @Param("canReadInstances") boolean canReadInstances, @Param("scope") String scope,
        @Param("status") String status, @Param("startedBy") Long startedBy,
        @Param("keyword") String keyword, @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to, @Param("limit") int limit,
        @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*)
        """ + INSTANCE_PAGE_FROM + INSTANCE_PAGE_WHERE + """
        </script>
        """)
    long countInstancePage(@Param("userId") long userId,
        @Param("admin") boolean admin, @Param("canReadTasks") boolean canReadTasks,
        @Param("canReadInstances") boolean canReadInstances, @Param("scope") String scope,
        @Param("status") String status, @Param("startedBy") Long startedBy,
        @Param("keyword") String keyword, @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to);
}
