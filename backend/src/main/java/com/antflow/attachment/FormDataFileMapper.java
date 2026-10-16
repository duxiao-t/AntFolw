package com.antflow.attachment;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FormDataFileMapper {
    /**
     * 关联一个附件。主键是 `(form_data_id, file_id)`——同一个文件在一条记录里只能挂一次，
     * 所以冲突时**更新归属字段**而不是 DO NOTHING：`reconcileEditable` 会先插"受限字段"的旧链接、
     * 再插"可编辑字段"的新链接，后者才是权威分类；DO NOTHING 会把这次字段迁移静默吞掉。
     */
    @Insert("""
        INSERT INTO t_form_data_file(form_data_id, file_id, field_id, sort_order)
        VALUES (#{formDataId}, #{fileId}, #{fieldId}, #{sortOrder})
        ON CONFLICT (form_data_id, file_id)
        DO UPDATE SET field_id = EXCLUDED.field_id, sort_order = EXCLUDED.sort_order
        """)
    void insertFileLink(@Param("formDataId") Long formDataId,
                        @Param("fileId") UUID fileId,
                        @Param("fieldId") String fieldId,
                        @Param("sortOrder") int sortOrder);

    @Delete("DELETE FROM t_form_data_file WHERE form_data_id = #{formDataId}")
    void deleteFileLinks(@Param("formDataId") Long formDataId);

    @Select("""
        SELECT file_id AS fileId, field_id AS fieldId, sort_order AS sortOrder
        FROM t_form_data_file
        WHERE form_data_id = #{formDataId}
        ORDER BY field_id, sort_order, file_id
        """)
    List<FormDataFileLink> selectFileLinks(@Param("formDataId") Long formDataId);

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

    record FormDataFileLink(java.util.UUID fileId, String fieldId, int sortOrder) {
    }

}
