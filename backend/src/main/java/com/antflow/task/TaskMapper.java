package com.antflow.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    @Select("SELECT * FROM t_task WHERE id = #{id} FOR UPDATE")
    TaskEntity selectForUpdate(@Param("id") Long id);
}
