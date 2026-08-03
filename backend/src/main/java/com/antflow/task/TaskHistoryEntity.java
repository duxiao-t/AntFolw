package com.antflow.task;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_task_history", autoResultMap = true)
public class TaskHistoryEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procInstId;
    private String fromNodeId;
    private String toNodeId;
    private Long taskId;
    private String action;     // START/APPROVE/REJECT/SKIP/WITHDRAW/COMPLETE
    private Long operatorId;
    private String comment;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
