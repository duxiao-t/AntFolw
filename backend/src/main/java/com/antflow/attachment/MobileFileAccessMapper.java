package com.antflow.attachment;

import java.util.UUID;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MobileFileAccessMapper {
    @Select("""
        SELECT DISTINCT pi.id
        FROM t_form_data_file fdf
        JOIN t_process_instance pi ON pi.form_data_id = fdf.form_data_id
        WHERE fdf.file_id = #{fileId}
        """)
    List<Long> selectLinkedInstanceIds(@Param("fileId") UUID fileId);

    @Select("SELECT form_data_id FROM t_form_data_file WHERE file_id = #{fileId}")
    List<Long> selectLinkedFormDataIds(@Param("fileId") UUID fileId);

    @Select("SELECT COUNT(*) FROM t_form_data_file WHERE file_id = #{fileId}")
    long countLinks(@Param("fileId") UUID fileId);
}
