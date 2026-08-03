package com.antflow.mobile.workflow;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MobileFileAccessMapper {
    @Select("""
        SELECT COUNT(DISTINCT pi.id)
        FROM t_form_data_file fdf
        JOIN t_process_instance pi ON pi.form_data_id = fdf.form_data_id
        LEFT JOIN t_task t ON t.proc_inst_id = pi.id
        WHERE fdf.file_id = #{fileId}
          AND (
              pi.started_by = #{userId}
              OR t.assignee_id = #{userId}
              OR t.approved_by = #{userId}
          )
        """)
    long countReadableProcessLinks(@Param("fileId") UUID fileId, @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM t_form_data_file WHERE file_id = #{fileId}")
    long countLinks(@Param("fileId") UUID fileId);
}
