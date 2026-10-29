package com.antflow.mobile.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MobileWorkflowMapper {
    /**
     * 持有 admin 角色的用户。这段以前放在 CompanySnapshotSql 里，但它与公司无关，
     * 单公司改造后随公司快照一起消失会有语义损失（通知/抄送要靠它放宽可见性），故内联保留。
     */
    String ADMIN = """
        EXISTS (SELECT 1 FROM t_user_role admin_user_role
          JOIN t_role admin_role ON admin_role.id = admin_user_role.role_id
          WHERE admin_user_role.user_id = #{userId}
            AND admin_role.code = 'admin' AND admin_role.enabled = true)
        """;

    /**
     * 通知可见性：管理员看全部；普通用户只看"确实指向一条存在的实例"的通知。
     * 以前还要求实例带公司归属快照，单公司下该门槛恒真，已移除。
     */
    String NOTIFICATION_SNAPSHOT = "(" + ADMIN + " OR " + """
        EXISTS (SELECT 1 FROM t_process_instance pi
          WHERE pi.id = NULLIF(payload->>'instanceId', '')::bigint))""";

    @Select("SELECT COUNT(*) FROM t_user_notification WHERE user_id = #{userId}"
        + " AND read_at IS NULL AND " + NOTIFICATION_SNAPSHOT)
    long countUnreadNotifications(@Param("userId") long userId);

    @Select("""
        <script>
        SELECT id, event_type, title,
               NULLIF(payload->>'instanceId', '')::bigint AS instance_id,
               NULLIF(payload->>'taskId', '')::bigint AS task_id,
               created_at, read_at
        FROM t_user_notification
        WHERE user_id = #{userId}
          AND
        """ + NOTIFICATION_SNAPSHOT + """
        <if test="unreadOnly">
          AND read_at IS NULL
        </if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<NotificationRow> selectNotifications(@Param("userId") long userId,
                                              @Param("unreadOnly") boolean unreadOnly,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Update("""
        UPDATE t_user_notification SET read_at = COALESCE(read_at, now())
        WHERE id = #{id} AND user_id = #{userId}
        """)
    int markNotificationRead(@Param("id") long id, @Param("userId") long userId);

    /** 抄送可见性：抄送记录指向的实例必须存在（单公司下不再叠加归属快照门槛）。 */
    String CC_SNAPSHOT = """
        EXISTS (SELECT 1 FROM t_process_instance pi
          WHERE pi.id = t_cc_record.proc_inst_id)""";

    @Select("SELECT COUNT(*) FROM t_cc_record WHERE recipient_id = #{userId}"
        + " AND read_at IS NULL AND " + CC_SNAPSHOT)
    long countUnreadCc(@Param("userId") long userId);

    @Update("""
        UPDATE t_cc_record SET read_at = COALESCE(read_at, now())
        WHERE id = #{id} AND recipient_id = #{userId}
          AND
        """ + CC_SNAPSHOT)
    int markCcRead(@Param("id") long id, @Param("userId") long userId);

    @Select("""
        <script>
        SELECT t.id,
               t.proc_inst_id AS instance_id,
               t.node_id,
               form.code AS form_code,
               form.name AS form_name,
               data.business_no,
               COALESCE(NULLIF(applicant.display_name, ''), applicant.username) AS applicant_name,
               applicant.employee_no AS applicant_employee_no,
               dept.name AS applicant_department,
               pi.process_snapshot::text AS process_snapshot,
               COALESCE(t.task_type, 'APPROVAL') AS task_type,
               t.status AS task_status,
               pi.status AS instance_status,
               t.created_at,
               t.read_at
        FROM (
          SELECT task.id, task.proc_inst_id, task.node_id, task.assignee_id,
                 task.task_type, task.status, task.created_at, task.read_at
          FROM t_task task
          UNION ALL
          SELECT 8000000000000000 + cc.id, cc.proc_inst_id, node.node_id, cc.recipient_id,
                 'CC', 'CC', cc.created_at, cc.read_at
          FROM t_cc_record cc
          LEFT JOIN t_process_node_instance node ON node.id = cc.node_instance_id
        ) t
        JOIN t_process_instance pi ON pi.id = t.proc_inst_id
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        LEFT JOIN t_user applicant ON applicant.id = pi.started_by
        LEFT JOIN t_department dept ON dept.id = applicant.dept_id
        WHERE t.assignee_id = #{userId}
        <choose>
          <when test="view == 'done'">
            AND (t.status IN ('APPROVED', 'REJECTED', 'RESUBMITTED')
              OR (t.status = 'CC' AND t.read_at IS NOT NULL))
          </when>
          <otherwise>
            AND (t.status = 'PENDING' OR (t.status = 'CC' AND t.read_at IS NULL))
          </otherwise>
        </choose>
        <if test="status != null and status != ''">
          AND t.status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (
            form.name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR applicant.display_name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR applicant.employee_no ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR dept.name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR data.business_no ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR t.node_id ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
          )
        </if>
        ORDER BY t.created_at DESC, t.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<TaskRow> selectTaskPage(@Param("userId") long userId,
                                 @Param("view") String view,
                                 @Param("keyword") String keyword,
                                 @Param("status") String status,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    @Select("""
        <script>
        SELECT pi.id,
               pi.status,
               form.name AS form_name,
               data.business_no,
               pi.current_node_id,
               pi.process_snapshot::text AS process_snapshot,
               pi.started_at,
               pi.finished_at
        FROM t_process_instance pi
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        WHERE pi.started_by = #{userId}
          AND pi.current_node_id IS DISTINCT FROM '__rework__'
        <if test="status != 'WITHDRAWN'">
          AND pi.status IS DISTINCT FROM 'WITHDRAWN'
        </if>
        <if test="status != null and status != ''">
          AND pi.status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (
            form.name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR data.business_no ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR pi.current_node_id ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
          )
        </if>
        ORDER BY pi.started_at DESC, pi.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<InstanceRow> selectInstancePage(@Param("userId") long userId,
                                         @Param("keyword") String keyword,
                                         @Param("status") String status,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("""
        <script>
        SELECT * FROM (
          SELECT 'WORKFLOW' AS kind,
                 pi.id,
                 pi.status,
                 form.name AS form_name,
                 data.business_no,
                 pi.current_node_id,
                 pi.process_snapshot::text AS process_snapshot,
                 pi.started_at,
                 pi.finished_at
          FROM t_process_instance pi
          JOIN t_form_data data ON data.id = pi.form_data_id
          JOIN t_form_definition form ON form.id = data.form_def_id
          WHERE pi.started_by = #{userId}
            AND pi.current_node_id IS DISTINCT FROM '__rework__'
          <if test="status != 'WITHDRAWN'">
            AND pi.status IS DISTINCT FROM 'WITHDRAWN'
          </if>
          UNION ALL
          SELECT 'DIRECT' AS kind,
                 data.id,
                 data.status,
                 form.name AS form_name,
                 data.business_no,
                 NULL AS current_node_id,
                 NULL AS process_snapshot,
                 data.created_at AS started_at,
                 data.created_at AS finished_at
          FROM t_form_data data
          JOIN t_form_definition form ON form.id = data.form_def_id
          WHERE data.created_by = #{userId}
            AND data.status = 'SUBMITTED'
            AND NOT EXISTS (
              SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = data.id
            )
        ) initiated
        WHERE 1 = 1
        <if test="status != null and status != ''">
          AND status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (
            form_name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR business_no ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR current_node_id ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
          )
        </if>
        ORDER BY started_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<InitiatedRow> selectInitiatedPage(@Param("userId") long userId,
                                           @Param("keyword") String keyword,
                                           @Param("status") String status,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Select("""
        SELECT data.id AS data_id,
               data.status,
               data.created_at,
               data.business_no,
               data.data::text AS form_data_json,
               form.code AS form_code,
               form.name AS form_name,
               form.schema::text AS form_schema
        FROM t_form_data data
        JOIN t_form_definition form ON form.id = data.form_def_id
        WHERE data.id = #{dataId}
          AND data.created_by = #{userId}
          AND data.status = 'SUBMITTED'
          AND NOT EXISTS (
            SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = data.id
          )
        """)
    DirectSubmissionRow selectDirectSubmission(@Param("dataId") long dataId,
                                                @Param("userId") long userId);

    @Select("""
        SELECT pi.id AS instance_id,
               pi.status AS instance_status,
               pi.current_node_id,
               pi.process_snapshot::text AS process_snapshot,
               pi.started_by,
               pi.started_at,
               pi.finished_at,
               pi.form_data_id,
               data.business_no,
               data.data::text AS form_data_json,
               form.code AS form_code,
               form.name AS form_name,
               COALESCE(form_version.schema, form.schema)::text AS form_schema,
               COALESCE(NULLIF(applicant.display_name, ''), applicant.username) AS applicant_name,
               applicant.employee_no AS applicant_employee_no,
               dept.name AS applicant_department
        FROM t_process_instance pi
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        LEFT JOIN t_form_data_revision form_revision ON form_revision.id = pi.current_form_revision_id
        LEFT JOIN t_form_definition_version form_version
          ON form_version.id = form_revision.form_definition_version_id
        LEFT JOIN t_user applicant ON applicant.id = pi.started_by
        LEFT JOIN t_department dept ON dept.id = applicant.dept_id
        WHERE pi.id = #{instanceId}
        """)
    InstanceDetailRow selectInstanceDetail(@Param("instanceId") Long instanceId);

    @Select("""
        SELECT t.id AS task_id,
               pi.id AS instance_id,
               t.node_id,
               t.assignee_id,
               t.parallel_id,
               t.node_instance_id,
               t.approval_mode,
               COALESCE(t.task_type, 'APPROVAL') AS task_type,
               t.status AS task_status,
               t.created_at AS task_created_at,
               t.read_at,
               pi.status AS instance_status,
               pi.current_node_id,
               pi.process_snapshot::text AS process_snapshot,
               pi.started_by,
               pi.started_at,
               pi.finished_at,
               pi.form_data_id,
               data.business_no,
               data.data::text AS form_data_json,
               form.code AS form_code,
               form.name AS form_name,
               COALESCE(form_version.schema, form.schema)::text AS form_schema,
               COALESCE(NULLIF(applicant.display_name, ''), applicant.username) AS applicant_name,
               applicant.employee_no AS applicant_employee_no,
               dept.name AS applicant_department
        FROM (
          SELECT task.id, task.proc_inst_id, task.node_id, task.assignee_id,
                 task.parallel_id, task.node_instance_id, task.approval_mode,
                 task.task_type, task.status,
                 task.created_at, task.read_at
          FROM t_task task
          UNION ALL
          SELECT 8000000000000000 + cc.id, cc.proc_inst_id, node.node_id, cc.recipient_id,
                 NULL, cc.node_instance_id, NULL, 'CC', 'CC', cc.created_at, cc.read_at
          FROM t_cc_record cc
          LEFT JOIN t_process_node_instance node ON node.id = cc.node_instance_id
        ) t
        JOIN t_process_instance pi ON pi.id = t.proc_inst_id
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        LEFT JOIN t_form_data_revision form_revision ON form_revision.id = pi.current_form_revision_id
        LEFT JOIN t_form_definition_version form_version
          ON form_version.id = form_revision.form_definition_version_id
        LEFT JOIN t_user applicant ON applicant.id = pi.started_by
        LEFT JOIN t_department dept ON dept.id = applicant.dept_id
        WHERE t.id = #{taskId}
        """)
    TaskDetailRow selectTaskDetail(@Param("taskId") Long taskId);

    @Select("""
        SELECT t.id AS task_id,
               t.node_id,
               t.task_type,
               t.status AS task_status,
               t.parallel_id,
               t.branch_id,
               COALESCE(t.operation_kind, CASE
                 WHEN t.is_additional = TRUE THEN 'ADD_ASSIGNEE'
                 ELSE (
                   SELECT h.action
                   FROM t_task_history h
                   WHERE h.task_id = t.parent_task_id
                     AND h.action IN ('TRANSFER', 'DELEGATE')
                   ORDER BY h.id DESC
                   LIMIT 1
                 )
               END) AS operation_kind,
               COALESCE(NULLIF(source_operator.display_name, ''), source_operator.username)
                   AS source_operator_name,
               COALESCE(NULLIF(operator.display_name, ''), operator.username, '未记录') AS operator_name,
               operator.employee_no,
               dept.name AS department,
               t.comment,
               t.created_at AS received_at,
               t.approved_at,
               t.read_at,
               COALESCE(runtime_node.round_no, 1 + (
                 SELECT COUNT(*) FROM t_task prior_rework
                 WHERE prior_rework.proc_inst_id = t.proc_inst_id
                   AND prior_rework.task_type = 'REWORK'
                   AND prior_rework.status = 'RESUBMITTED'
                   AND prior_rework.created_at < t.created_at
               ))::int AS round_no
        FROM (
          SELECT task.id, task.proc_inst_id, task.node_id, task.task_type, task.status,
                  task.parallel_id, task.branch_id, task.operation_kind,
                  task.is_additional, task.parent_task_id, task.assignee_id,
                  task.approved_by, task.delegated_from, task.comment,
                  task.created_at, task.approved_at, task.read_at, task.node_instance_id
          FROM t_task task
          UNION ALL
          SELECT 8000000000000000 + cc.id, cc.proc_inst_id, node.node_id, 'CC', 'CC',
                  NULL, node.branch_id, 'CC', false, NULL, cc.recipient_id,
                  NULL, NULL, NULL, cc.created_at, NULL, cc.read_at, cc.node_instance_id
          FROM t_cc_record cc
          LEFT JOIN t_process_node_instance node ON node.id = cc.node_instance_id
        ) t
        LEFT JOIN t_process_node_instance runtime_node ON runtime_node.id = t.node_instance_id
        LEFT JOIN t_task parent_task ON parent_task.id = t.parent_task_id
        LEFT JOIN t_user operator ON operator.id = COALESCE(t.approved_by, t.assignee_id)
        LEFT JOIN t_user source_operator
          ON source_operator.id = COALESCE(t.delegated_from, parent_task.assignee_id)
        LEFT JOIN t_department dept ON dept.id = operator.dept_id
        WHERE t.proc_inst_id = #{instanceId}
        ORDER BY t.created_at, t.id
        """)
    List<ApprovalRow> selectApprovalTasks(@Param("instanceId") Long instanceId);

    @Select("""
        SELECT pi.id AS instance_id,
               form.code AS form_code,
               form.name AS form_title,
               pi.status,
               COALESCE(pi.finished_at, pi.started_at) AS updated_at
        FROM t_process_instance pi
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        WHERE pi.started_by = #{userId}
          AND pi.status IS DISTINCT FROM 'WITHDRAWN'
        ORDER BY pi.started_at DESC, pi.id DESC
        LIMIT #{limit}
        """)
    List<RecentProcessDto> selectRecentProcesses(@Param("userId") long userId,
                                                 @Param("limit") int limit);

    record TaskRow(Long id, Long instanceId, String nodeId, String formCode,
                   String formName, String businessNo, String applicantName,
                   String applicantEmployeeNo, String applicantDepartment,
                   String processSnapshot, String taskType, String taskStatus,
                   String instanceStatus, OffsetDateTime createdAt, OffsetDateTime readAt) {
    }

    record InstanceRow(Long id, String status, String formName, String businessNo,
                       String currentNodeId, String processSnapshot,
                       OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    record InitiatedRow(String kind, Long id, String status, String formName, String businessNo,
                        String currentNodeId, String processSnapshot,
                        OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    record DirectSubmissionRow(Long dataId, String status, OffsetDateTime createdAt,
                               String businessNo, String formDataJson, String formCode,
                               String formName, String formSchema) {
    }

    record InstanceDetailRow(Long instanceId, String instanceStatus, String currentNodeId,
                             String processSnapshot, Long startedBy,
                             OffsetDateTime startedAt, OffsetDateTime finishedAt,
                             Long formDataId, String businessNo, String formDataJson,
                             String formCode, String formName, String formSchema,
                             String applicantName, String applicantEmployeeNo,
                             String applicantDepartment) {
    }

    record TaskDetailRow(Long taskId, Long instanceId, String nodeId, Long assigneeId,
                          String parallelId, Long nodeInstanceId,
                          String approvalMode, String taskType, String taskStatus,
                         OffsetDateTime taskCreatedAt,
                         OffsetDateTime readAt, String instanceStatus, String currentNodeId,
                         String processSnapshot, Long startedBy, OffsetDateTime startedAt,
                         OffsetDateTime finishedAt, Long formDataId, String businessNo,
                         String formDataJson, String formCode, String formName,
                         String formSchema, String applicantName, String applicantEmployeeNo,
                         String applicantDepartment) {
    }

    record ApprovalRow(Long taskId, String nodeId, String taskType, String taskStatus,
                       String parallelId, String branchId, String operationKind,
                       String sourceOperatorName, String operatorName, String employeeNo, String department,
                       String comment, OffsetDateTime receivedAt, OffsetDateTime approvedAt,
                       OffsetDateTime readAt, Integer roundNo) {
    }

    record NotificationRow(Long id, String eventType, String title, Long instanceId,
                           Long taskId, OffsetDateTime createdAt, OffsetDateTime readAt) {
    }
}
