package com.antflow.task;

import com.antflow.form.handler.JsonbJacksonTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_process_instance", autoResultMap = true)
public class ProcessInstance {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procDefId;
    private Integer processDefVersion;  // 冻结的版本号（V5 新增）
    @TableField(typeHandler = JsonbJacksonTypeHandler.class)
    private String processSnapshot;     // 冻结的流程树 JSONB（V5 新增）
    private Long formDataId;
    private String status;       // RUNNING/APPROVED/REJECTED/WITHDRAWN
    private String currentNodeId;
    @Version private Integer version;
    private Long startedBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime startedAt;
    private java.time.OffsetDateTime finishedAt;
}
