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
    private Long currentRevisionId;
    @TableField(typeHandler = JsonbJacksonTypeHandler.class)
    private String data;        // JSONB
    private String status;      // DRAFT or SUBMITTED
    private Long createdBy;
    /** 提交人姓名（display_name 为空时回落登录账号，口径同 ProcessInstanceMapper 的 COALESCE）。 */
    @TableField(exist = false) private String createdByName;
    @TableField(exist = false) private String createdByEmployeeNo;
    @TableField(exist = false) private String createdByDeptName;
    @TableField(exist = false) private List<FieldValue> fieldValues = List.of();
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private java.time.OffsetDateTime updatedAt;

    /**
     * 台账/导出的一个字段值。{@code displayText}/{@code detailText} 是后端按**该记录自己的版本**
     * 解析出的显示文本（见 {@link FormValueDisplay}）；{@code value} 保留原始值，动态列等消费者还要用。
     */
    public record FieldValue(String fieldId, String fieldName, Object value,
                             String displayText, String detailText) {}
}
