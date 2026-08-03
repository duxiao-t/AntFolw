package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_user_role", autoResultMap = true)
public class UserRole {
    private Long userId;
    private Long roleId;
}
