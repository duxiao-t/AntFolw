package com.antflow.common;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("t_idempotency_record")
public class IdempotencyRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private String httpMethod;
    private String requestPath;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private Integer responseStatus;
    private String responseBody;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
