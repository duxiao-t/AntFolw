package com.antflow.form.runtime;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
