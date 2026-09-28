package com.antflow.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {
    @Update("""
        UPDATE t_auth_session
        SET refresh_token_hash = #{session.refreshTokenHash},
            csrf_token_hash = #{session.csrfTokenHash},
            last_active_at = #{session.lastActiveAt},
            device_name = #{session.deviceName},
            platform = #{session.platform}
        WHERE id = #{session.id} AND refresh_token_hash = #{previousRefreshTokenHash}
          AND revoked_at IS NULL AND expires_at > now()
        """)
    int rotate(@Param("session") AuthSession session,
               @Param("previousRefreshTokenHash") String previousRefreshTokenHash);
}
