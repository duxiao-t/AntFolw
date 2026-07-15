package com.antflow.form;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

@Data
@TableName(value = "t_form_definition", autoResultMap = true)
public class FormDefinition {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private Integer version;
    @TableField(typeHandler = JacksonTypeHandler.class) private String schema;       // JSONB
    @TableField(typeHandler = JacksonTypeHandler.class) private String settings;     // JSONB
    private String status;       // DRAFT / PUBLISHED / DEPRECATED
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private java.time.OffsetDateTime updatedAt;
}
