package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
@TableName(value = "t_user", autoResultMap = true)
public class User {
    @TableId(type = IdType.AUTO) private Long id;
    private Long deptId;
    private Long managerId;
    @TableField(exist = false) private String managerDisplayName;
    private String employeeNo;
    private String username;
    @JsonIgnore
    private String passwordHash;
    private String displayName;
    private String email;
    private String phone;
    private String position;
    private String gender;
    private String status;
    private Long authzVersion;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
