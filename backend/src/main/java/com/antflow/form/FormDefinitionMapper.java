package com.antflow.form;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FormDefinitionMapper extends BaseMapper<FormDefinition> {
    @Select("""
        <script>
        SELECT form.id, form.code, form.name, form.description, form.version, form.status,
               form.created_by, form.authz_version, form.created_at, form.updated_at
        FROM t_form_definition form
        WHERE form.deleted = 0
        <if test="keyword != null and keyword != ''">
          AND (form.name ILIKE CONCAT('%', #{keyword}, '%')
            OR form.code ILIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="status != null and status != ''">
          AND form.status = #{status}
        </if>
        <if test="!admin">
          AND form.id IN (
            SELECT grant_row.form_def_id
            FROM t_form_resource_grant grant_row
            WHERE (grant_row.subject_type = 'USER' AND grant_row.subject_id = #{userId})
               OR (grant_row.subject_type = 'ROLE' AND grant_row.subject_id IN (
                 SELECT user_role.role_id
                 FROM t_user_role user_role
                 JOIN t_role role ON role.id = user_role.role_id AND role.enabled = true
                 WHERE user_role.user_id = #{userId}
               ))
          )
        </if>
        ORDER BY form.updated_at DESC, form.id DESC
        </script>
        """)
    Page<Summary> selectSummaryPage(Page<Summary> page,
                                    @Param("keyword") String keyword,
                                    @Param("status") String status,
                                    @Param("userId") Long userId,
                                    @Param("admin") boolean admin);

    record Summary(Long id, String code, String name, String description, Integer version,
                   String status, Long createdBy, Integer authzVersion,
                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
