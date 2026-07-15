package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "t_user_role", autoResultMap = true)
public class UserRole {
    private Long userId;
    private Long roleId;
}
