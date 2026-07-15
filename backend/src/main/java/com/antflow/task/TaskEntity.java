package com.antflow.task;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_task", autoResultMap = true)
public class TaskEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procInstId;
    private String nodeId;
    private Long assigneeId;
    private String status;       // PENDING/APPROVED/REJECTED/SKIPPED
    private String approvalMode; // OR_SIGN (default); ALL_SIGN reserved for v1.x
    @Version private Integer version;
    private Long approvedBy;
    private java.time.OffsetDateTime approvedAt;
    private String comment;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
