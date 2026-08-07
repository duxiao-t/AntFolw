package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_role", autoResultMap = true)
public class Role {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private String description;
    private String dataScope;
    private Boolean enabled;
    private Boolean builtin;
    @Version private Integer version;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private java.time.OffsetDateTime updatedAt;
}
