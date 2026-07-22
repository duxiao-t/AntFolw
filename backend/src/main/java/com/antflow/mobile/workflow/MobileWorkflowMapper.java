package com.antflow.mobile.workflow;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;

@Mapper
public interface MobileWorkflowMapper {
    @Select("""
        <script>
        SELECT t.*
        FROM t_task t
        JOIN t_process_instance pi ON pi.id = t.proc_inst_id
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        LEFT JOIN t_user applicant ON applicant.id = pi.started_by
        LEFT JOIN t_department dept ON dept.id = applicant.dept_id
        WHERE t.assignee_id = #{userId}
        <choose>
          <when test="view == 'done'">
            AND t.status != 'PENDING'
          </when>
          <otherwise>
            AND t.status = 'PENDING'
          </otherwise>
        </choose>
        <if test="status != null and status != ''">
          AND t.status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (
            form.name ILIKE CONCAT('%', #{keyword}, '%')
            OR applicant.display_name ILIKE CONCAT('%', #{keyword}, '%')
            OR dept.name ILIKE CONCAT('%', #{keyword}, '%')
            OR t.node_id ILIKE CONCAT('%', #{keyword}, '%')
          )
        </if>
        ORDER BY t.created_at DESC, t.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<TaskEntity> selectTaskPage(@Param("userId") long userId,
                                    @Param("view") String view,
                                    @Param("keyword") String keyword,
                                    @Param("status") String status,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("""
        <script>
        SELECT pi.*
        FROM t_process_instance pi
        JOIN t_form_data data ON data.id = pi.form_data_id
        JOIN t_form_definition form ON form.id = data.form_def_id
        WHERE pi.started_by = #{userId}
        <if test="status != null and status != ''">
          AND pi.status = #{status}
        </if>
        <if test="keyword != null and keyword != ''">
          AND (
            form.name ILIKE CONCAT('%', #{keyword}, '%')
            OR pi.current_node_id ILIKE CONCAT('%', #{keyword}, '%')
          )
        </if>
        ORDER BY pi.started_at DESC, pi.id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<ProcessInstance> selectInstancePage(@Param("userId") long userId,
                                             @Param("keyword") String keyword,
                                             @Param("status") String status,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    @Insert("""
        INSERT INTO t_form_data_file(form_data_id, file_id, field_id, sort_order)
        VALUES (#{formDataId}, #{fileId}, #{fieldId}, #{sortOrder})
        """)
    void insertFileLink(@Param("formDataId") Long formDataId,
                        @Param("fileId") UUID fileId,
                        @Param("fieldId") String fieldId,
                        @Param("sortOrder") int sortOrder);

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
}
