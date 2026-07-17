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
    private String status;       // PENDING/APPROVED/REJECTED/SKIPPED/CC
    private String approvalMode; // OR_SIGN (default); ALL_SIGN reserved for v1.x
    @Version private Integer version;
    private Long approvedBy;
    private java.time.OffsetDateTime approvedAt;
    private String comment;
    /** 父任务 id（加签/转交产生的子任务）；null 表示原始任务 */
    private Long parentTaskId;
    /** 委托来源 user_id（forwardee 真正审批，原始 assignee 仅审计） */
    private Long delegatedFrom;
    /** 是否加签任务（与原任务 OR/AND 合并决签） */
    private Boolean isAdditional;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
