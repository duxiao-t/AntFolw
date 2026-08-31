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
    @Select("SELECT COUNT(*) FROM t_user_notification WHERE user_id = #{userId} AND read_at IS NULL")
    long countUnreadNotifications(@Param("userId") long userId);

    @Select("""
        <script>
        SELECT id, event_type, title,
               NULLIF(payload->>'instanceId', '')::bigint AS instance_id,
               NULLIF(payload->>'taskId', '')::bigint AS task_id,
               created_at, read_at
        FROM t_user_notification
        WHERE user_id = #{userId}
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

    @Select("SELECT COUNT(*) FROM t_cc_record WHERE recipient_id = #{userId} AND read_at IS NULL")
    long countUnreadCc(@Param("userId") long userId);

    @Update("""
        UPDATE t_cc_record SET read_at = COALESCE(read_at, now())
        WHERE id = #{id} AND recipient_id = #{userId}
        """)
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
            form.name ILIKE CONCAT('%', #{keyword}, '%')
            OR applicant.display_name ILIKE CONCAT('%', #{keyword}, '%')
            OR applicant.employee_no ILIKE CONCAT('%', #{keyword}, '%')
            OR dept.name ILIKE CONCAT('%', #{keyword}, '%')
            OR data.business_no ILIKE CONCAT('%', #{keyword}, '%')
            OR t.node_id ILIKE CONCAT('%', #{keyword}, '%')
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
        <if test="status != null and status != ''">
          AND pi.status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (
            form.name ILIKE CONCAT('%', #{keyword}, '%')
            OR data.business_no ILIKE CONCAT('%', #{keyword}, '%')
            OR pi.current_node_id ILIKE CONCAT('%', #{keyword}, '%')
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
                 task.parallel_id, task.node_instance_id, task.task_type, task.status,
                 task.created_at, task.read_at
          FROM t_task task
          UNION ALL
          SELECT 8000000000000000 + cc.id, cc.proc_inst_id, node.node_id, cc.recipient_id,
                 NULL, cc.node_instance_id, 'CC', 'CC', cc.created_at, cc.read_at
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
        ORDER BY pi.started_at DESC, pi.id DESC
        LIMIT #{limit}
        """)
    List<RecentProcessDto> selectRecentProcesses(@Param("userId") long userId,
                                                 @Param("limit") int limit);

    @Insert("""
        INSERT INTO t_form_data_file(form_data_id, file_id, field_id, sort_order)
        VALUES (#{formDataId}, #{fileId}, #{fieldId}, #{sortOrder})
        """)
    void insertFileLink(@Param("formDataId") Long formDataId,
                        @Param("fileId") UUID fileId,
                        @Param("fieldId") String fieldId,
                        @Param("sortOrder") int sortOrder);

    @Delete("DELETE FROM t_form_data_file WHERE form_data_id = #{formDataId}")
    void deleteFileLinks(@Param("formDataId") Long formDataId);

    @Select("""
        SELECT mf.*
        FROM t_mobile_file mf
        JOIN t_form_data_file fdf ON fdf.file_id = mf.id
        WHERE fdf.form_data_id = #{formDataId}
          AND mf.status = 'READY'
          AND mf.deleted_at IS NULL
        ORDER BY fdf.field_id, fdf.sort_order, mf.created_at
        """)
    List<MobileFile> selectFilesByFormDataId(@Param("formDataId") Long formDataId);

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
                          String taskType, String taskStatus, OffsetDateTime taskCreatedAt,
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
