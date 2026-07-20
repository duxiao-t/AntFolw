package com.antflow.mobile.workflow;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MobileWorkflowMapper {
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
