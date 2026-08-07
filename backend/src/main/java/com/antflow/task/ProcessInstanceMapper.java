package com.antflow.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProcessInstanceMapper extends BaseMapper<ProcessInstance> {
    @Select("SELECT * FROM t_process_instance WHERE id = #{id} FOR UPDATE")
    ProcessInstance selectForUpdate(@Param("id") Long id);
}
