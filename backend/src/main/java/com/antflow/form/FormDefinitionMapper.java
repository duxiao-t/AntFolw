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
          <!-- 必须显式转成 text：连接串带 stringtype=unspecified，参数以 unknown 送到 PG，
               而 concat(variadic "any") 无法从 unknown 推断类型，会直接 500。
               手机端 MobileWorkflowMapper 同样的问题也一并补上了。 -->
          AND (form.name ILIKE CONCAT('%', CAST(#{keyword} AS text), '%')
            OR form.code ILIKE CONCAT('%', CAST(#{keyword} AS text), '%'))
        </if>
        <if test="status != null and status != ''">
          AND form.status = #{status}
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
