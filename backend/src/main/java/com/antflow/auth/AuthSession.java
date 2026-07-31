package com.antflow.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("t_auth_session")
public class AuthSession {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private Long userId;
    private String refreshTokenHash;
    private String csrfTokenHash;
    private String deviceName;
    private String platform;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActiveAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime revokedAt;
}
