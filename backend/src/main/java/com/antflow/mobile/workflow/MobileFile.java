package com.antflow.mobile.workflow;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("t_mobile_file")
public class MobileFile {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private Long ownerId;
    private String originalName;
    private String storageKey;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private OffsetDateTime deletedAt;
}
