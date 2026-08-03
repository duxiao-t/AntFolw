package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_company", autoResultMap = true)
public class Company {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
