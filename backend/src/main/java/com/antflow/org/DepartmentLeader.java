package com.antflow.org;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_department_leader", autoResultMap = true)
public class DepartmentLeader {
    private Long departmentId;
    private Long userId;
}
