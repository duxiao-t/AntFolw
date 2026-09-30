package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {

    /**
     * Returns this department AND all its descendants via ltree's `<@`
     * ("is descendant of including") operator.
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE dept_id = #{departmentId}")
    long countUsers(@Param("departmentId") Long departmentId);
    @Select("SELECT * FROM t_department WHERE path <@ CAST(#{path} AS ltree) ORDER BY path")
    List<Department> subtree(@Param("path") String path);

    @Select("""
        SELECT child.id
        FROM t_department child
        JOIN t_department parent ON parent.id = #{departmentId}
        WHERE child.path <@ parent.path
        ORDER BY child.path, child.id
        """)
    List<Long> subtreeIds(@Param("departmentId") Long departmentId);

    /**
     * 「关键字命中部门名」也要能搜出人——且是命中部门的**全部下级**的成员（搜"研发"得看到研发部下各组）。
     *
     * <p>以前是在 Java 里先查部门 id 再 {@code in("dept_id", ids)}：只命中同级、结果集还可能很大。
     * 这里让数据库用 ltree 一次算完，调用方只管把谓词 OR 进自己的关键字分支里。
     *
     * <p>和 {@link #subtreeIds} 放一起是因为同属 ltree 查询知识；写成静态方法是为了让调用方
     * 单测能直接看到拼出来的 SQL（mapper 的 default 方法会被 mock 掉，看不见分毫）。
     */
    static void applyDeptNameMatch(QueryWrapper<User> query, String keyword) {
        query.or().apply("dept_id IN (SELECT child.id FROM t_department child"
            + " WHERE EXISTS (SELECT 1 FROM t_department hit"
            + " WHERE hit.name LIKE {0} AND child.path <@ hit.path))", "%" + keyword + "%");
    }
}
