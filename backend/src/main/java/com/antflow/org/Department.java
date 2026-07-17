package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.List;

@Data
@TableName(value = "t_department", autoResultMap = true)
public class Department {
    @TableId(type = IdType.AUTO) private Long id;
    private Long companyId;
    private Long parentId;
    /** `ltree` materialized path; e.g. `acme.root.eng.platform`. */
    private String path;
    private String name;
    private Long leaderId;
    private Integer sortOrder;
    @TableField(exist = false) private List<Long> leaderIds = List.of();
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
