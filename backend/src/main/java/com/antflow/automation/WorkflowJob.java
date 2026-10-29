package com.antflow.automation;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("t_workflow_job")
public class WorkflowJob {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procInstId;
    private Long taskId;
    private Long nodeInstanceId;
    private String nodeId;
    private String jobType;
    private String actionKey;
    /** 幂等键的一部分：同一节点在每一轮都可以各有一条 DELAY/TRIGGER 作业（见 V60）。 */
    private Integer roundNo;
    private OffsetDateTime scheduledAt;
    private String status;
    private Integer attempts;
    private Integer maxAttempts;
    private UUID deliveryId;
    private String payload;
    private Boolean blocking;
    private String lastError;
    /** 最近一次投递的 HTTP 状态码与响应体（截断），成功/失败都记，便于排障。 */
    private Integer responseStatus;
    private String responseBody;
    private OffsetDateTime lockedAt;
    private String lockedBy;
    private OffsetDateTime completedAt;
    @TableField(fill = FieldFill.INSERT) private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private OffsetDateTime updatedAt;
}
