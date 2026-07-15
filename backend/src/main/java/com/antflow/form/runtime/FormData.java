package com.antflow.form.runtime;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

@Data
@TableName(value = "t_form_data", autoResultMap = true)
public class FormData {
    @TableId(type = IdType.AUTO) private Long id;
    private Long formDefId;
    private Integer formDefVersion;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private String data;        // JSONB
    private String status;      // DRAFT or SUBMITTED
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
