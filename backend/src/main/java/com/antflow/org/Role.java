package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_role", autoResultMap = true)
public class Role {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
}
