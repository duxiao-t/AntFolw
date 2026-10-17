package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_company", autoResultMap = true)
public class Company {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
    /** 0=正常，1=已停用（软删除）。MyBatis-Plus 全局 logic-delete-field 自动过滤。 */
    private Integer deleted;
}
