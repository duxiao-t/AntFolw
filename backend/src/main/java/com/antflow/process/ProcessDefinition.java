package com.antflow.process;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

@Data
@TableName(value = "t_process_definition", autoResultMap = true)
public class ProcessDefinition {
    @TableId(type = IdType.AUTO) private Long id;
    private Long formDefId;
    private Integer version;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String process;     // JSONB 流程树
    private String status;
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
