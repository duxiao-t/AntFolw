package com.antflow.form.runtime;

import com.baomidou.mybatisplus.annotation.*;
import com.antflow.form.handler.JsonbJacksonTypeHandler;
import lombok.Data;

import java.util.List;

@Data
@TableName(value = "t_form_data", autoResultMap = true)
public class FormData {
    @TableId(type = IdType.AUTO) private Long id;
    private Long formDefId;
    private Integer formDefVersion;
    private String businessNo;
    @TableField(typeHandler = JsonbJacksonTypeHandler.class)
    private String data;        // JSONB
    private String status;      // DRAFT or SUBMITTED
    private Long createdBy;
    @TableField(exist = false) private String createdByUsername;
    @TableField(exist = false) private List<FieldValue> fieldValues = List.of();
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private java.time.OffsetDateTime updatedAt;

    public record FieldValue(String fieldId, String fieldName, Object value) {}
}
