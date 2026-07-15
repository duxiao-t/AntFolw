package com.antflow.org;

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
    @Select("SELECT * FROM t_department WHERE path <@ CAST(#{path} AS ltree) ORDER BY path")
    List<Department> subtree(@Param("path") String path);
}
