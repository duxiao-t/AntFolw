package com.antflow.mobile.workflow;

import com.antflow.form.handler.JsonbJacksonTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName(value = "t_mobile_app_preference", autoResultMap = true)
public class MobileAppPreference {
    @TableId(type = IdType.INPUT)
    private Long userId;

    @TableField(typeHandler = JsonbJacksonTypeHandler.class)
    private String formIds;

    private OffsetDateTime updatedAt;
}
