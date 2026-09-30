package com.antflow.form.runtime;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FormDataMapper extends BaseMapper<FormData> {

    /**
     * 本人提交列表：自助入口，只按 created_by 过滤，不受行级数据权限约束。
     *
     * <p>用独立 mapper 方法 + {@code @InterceptorIgnore} 是刻意为之：MyBatis-Plus 的
     * {@code selectPage} 内部执行的是 {@code selectList} 语句，共用语句 id 会导致
     * 数据权限拦截器无法只作用于分页列表。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("""
        <script>
        SELECT * FROM t_form_data
        WHERE created_by = #{userId}
        <if test="formDefId != null"> AND form_def_id = #{formDefId} </if>
        ORDER BY created_at DESC, id DESC
        </script>
        """)
    List<FormData> selectMySubmissions(@Param("userId") long userId,
                                       @Param("formDefId") Long formDefId);

    /** 本人草稿是自助数据，不依赖管理端的 form:data:read 能力。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("""
        SELECT * FROM t_form_data
        WHERE created_by = #{userId} AND status = 'DRAFT'
        ORDER BY updated_at DESC, id DESC
        """)
    List<FormData> selectMyDrafts(@Param("userId") long userId);

    /**
     * 台账/导出按**每条记录自己的版本**解析 schema：实例当前修订版 → 该行的
     * (form_def_id, form_def_version) 快照 → 当前定义。JOIN 顺序与
     * {@code OptionRuntimeService.schema()} 一字不差，否则同一条记录在台账与选项接口里会是两套字段。
     *
     * <p>软删表单取的是当前定义那一栏（`f.schema`），不筛 `deleted`——历史单据还得看得见。
     *
     * <p>导出上限 1 万行 → 最坏 1 万个参数，Postgres 上限 65535，不分批也放得下。
     */
    @Select("""
        <script>
        SELECT d.id AS data_id, COALESCE(v.schema, legacy.schema, f.schema)::text AS schema
        FROM t_form_data d
        JOIN t_form_definition f ON f.id = d.form_def_id
        LEFT JOIN t_process_instance i ON i.form_data_id = d.id
        LEFT JOIN t_form_data_revision r ON r.id = i.current_form_revision_id
        LEFT JOIN t_form_definition_version v ON v.id = r.form_definition_version_id
        LEFT JOIN t_form_definition_version legacy
          ON legacy.form_definition_id = d.form_def_id AND legacy.version_no = d.form_def_version
        WHERE d.id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<SchemaRow> selectSchemas(@Param("ids") Collection<Long> ids);

    record SchemaRow(Long dataId, String schema) { }
}
