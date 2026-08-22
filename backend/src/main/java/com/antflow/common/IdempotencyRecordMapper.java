package com.antflow.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
    @Insert("""
        INSERT INTO t_idempotency_record
            (user_id, http_method, request_path, idempotency_key, request_hash, status, expires_at)
        VALUES (#{userId}, #{method}, #{path}, #{key}, #{requestHash}, 'PROCESSING', #{expiresAt})
        ON CONFLICT (user_id, http_method, request_path, idempotency_key)
        DO UPDATE SET request_hash = EXCLUDED.request_hash,
                      status = 'PROCESSING', response_status = NULL, response_body = NULL,
                      expires_at = EXCLUDED.expires_at, updated_at = now()
        WHERE (t_idempotency_record.status = 'FAILED'
               AND t_idempotency_record.request_hash = EXCLUDED.request_hash)
           OR t_idempotency_record.expires_at <= now()
        """)
    int tryClaim(@Param("userId") long userId, @Param("method") String method,
                 @Param("path") String path, @Param("key") String key,
                 @Param("requestHash") String requestHash,
                 @Param("expiresAt") OffsetDateTime expiresAt);

    @Select("SELECT * FROM t_idempotency_record WHERE user_id = #{userId} AND http_method = #{method} AND request_path = #{path} AND idempotency_key = #{key}")
    IdempotencyRecord find(@Param("userId") long userId, @Param("method") String method,
                           @Param("path") String path, @Param("key") String key);

    @Update("""
        UPDATE t_idempotency_record
           SET status = 'SUCCEEDED', response_status = #{status}, response_body = #{body}, updated_at = now()
         WHERE id = #{id} AND status = 'PROCESSING' AND request_hash = #{requestHash}
        """)
    int markSucceeded(@Param("id") long id, @Param("requestHash") String requestHash,
                      @Param("status") int status, @Param("body") String body);

    @Update("""
        UPDATE t_idempotency_record SET status = 'FAILED', updated_at = now()
         WHERE id = #{id} AND status = 'PROCESSING' AND request_hash = #{requestHash}
        """)
    int markFailed(@Param("id") long id, @Param("requestHash") String requestHash);

    @Delete("DELETE FROM t_idempotency_record WHERE expires_at <= now()")
    int deleteExpired();
}
