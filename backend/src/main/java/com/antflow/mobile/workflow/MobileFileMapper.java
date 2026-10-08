package com.antflow.mobile.workflow;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 上传件的持久层。
 *
 * <p>带 {@code @InterceptorIgnore(dataPermission = "true")} 的几条是刻意的：这些语句要么在租约/终态
 * 的原子更新里（不能带额外的行级过滤，否则"条件更新"可能因为注入的条件而假失败），要么在鉴权之前的
 * 行锁里（`t_mobile_file` 本来也没有数据权限规则）。仓库里 `WorkflowJobMapper` 是同样的写法。
 */
@Mapper
public interface MobileFileMapper extends BaseMapper<MobileFile> {

    /**
     * 认领一行的异步处理权。返回 0 表示**别人已经认领**（或行已不在 PROCESSING），调用方必须放弃——
     * 这是"同一行只跑一次"的唯一判据。
     */
    @Update("""
        UPDATE t_mobile_file
        SET processing_claim_token = #{token}, processing_claimed_at = now()
        WHERE id = #{id} AND status = 'PROCESSING' AND processing_claim_token IS NULL
        """)
    @InterceptorIgnore(dataPermission = "true")
    int claimProcessing(@Param("id") UUID id, @Param("token") UUID token);

    /**
     * 续租。**处理期间要反复调用**（下载后、ffmpeg 前后）：一次 ffmpeg 最长 10 分钟，
     * 只在开头续一次的话租约早就过期，reaper 会把同一行抢走。
     * 按 token 条件更新——租约已被别人拿走时这里改不动任何行，调用方据此知道"我出局了"。
     */
    @Update("""
        UPDATE t_mobile_file SET processing_claimed_at = now()
        WHERE id = #{id} AND status = 'PROCESSING' AND processing_claim_token = #{token}
        """)
    @InterceptorIgnore(dataPermission = "true")
    int renewProcessingClaim(@Param("id") UUID id, @Param("token") UUID token);

    /** 只释放**自己那次**认领；迟到的 release 不会抹掉别人刚建立的租约。 */
    @Update("""
        UPDATE t_mobile_file SET processing_claim_token = NULL, processing_claimed_at = NULL
        WHERE id = #{id} AND status = 'PROCESSING' AND processing_claim_token = #{token}
        """)
    @InterceptorIgnore(dataPermission = "true")
    int releaseProcessingClaim(@Param("id") UUID id, @Param("token") UUID token);

    /**
     * 领取"没人认领或租约已过期"的 PROCESSING 行（reaper/启动恢复用），返回本次抢到的 id。
     *
     * <p>条件里的 {@code processing_claimed_at IS NULL} 不能省：迁移前遗留下来的行、以及上传后还没
     * 被认领的行都是 NULL，只写 {@code < staleBefore} 会永远捞不到——恰恰是这些行最需要被恢复。
     *
     * <p>`FOR UPDATE SKIP LOCKED` 让多实例/多轮之间不会互相阻塞（范式同
     * {@code WorkflowJobMapper.claimDue}，但这里的结果由 worker 自己续租，见 {@link #renewProcessingClaim}）。
     */
    @Select("""
        WITH candidate AS (
            SELECT id FROM t_mobile_file
            WHERE status = 'PROCESSING'
              AND (processing_claimed_at IS NULL OR processing_claimed_at < #{staleBefore})
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT #{limit}
        )
        UPDATE t_mobile_file file
        SET processing_claim_token = #{token}, processing_claimed_at = now()
        FROM candidate
        WHERE file.id = candidate.id
        RETURNING file.id
        """)
    @InterceptorIgnore(dataPermission = "true")
    List<UUID> claimStaleProcessing(@Param("staleBefore") OffsetDateTime staleBefore,
                                    @Param("limit") int limit,
                                    @Param("token") UUID token);

    /**
     * 找同一 owner 下已存在的同内容文件，**加行锁**（拿到它之后的补写/返回必须和它同一个事务，
     * 否则并发 {@code delete} 会插进"查到"和"写回去"之间）。
     *
     * <p>`ORDER BY ... LIMIT 1` 不能省：`FOR UPDATE` 对**还不存在**的行锁不住任何东西，两个并发首次
     * 上传同一份字节会各插一行（表上没有 (owner_id, sha256) 唯一约束），不限行的话读到的多行会让
     * `selectOne` 抛 `TooManyResultsException` → 下一次上传这份字节直接 500。这里固定取最早那行。
     *
     * <p>用显式 SQL 而不是 QueryWrapper：MP 的 wrapper 会把 `last()` 拼在 `ORDER BY` **之前**、再自己
     * 追加一个 `LIMIT 1`，组合出来是 `FOR UPDATE ORDER BY ... LIMIT 1`（非法）。
     */
    @Select("""
        SELECT * FROM t_mobile_file
        WHERE owner_id = #{ownerId} AND sha256 = #{sha256}
          AND status = 'READY' AND deleted_at IS NULL
        ORDER BY created_at, id
        LIMIT 1
        FOR UPDATE
        """)
    @InterceptorIgnore(dataPermission = "true")
    MobileFile selectReadyDuplicateForUpdate(@Param("ownerId") long ownerId,
                                             @Param("sha256") String sha256);

    /**
     * 所有行引用着的对象 key（孤儿清扫器的判据）。不筛 `status`：PROCESSING 的行引用的正是它即将
     * 被替换掉的源对象，排队等转码期间那也是活的。
     */
    @Select("SELECT storage_key FROM t_mobile_file WHERE storage_key IS NOT NULL")
    @InterceptorIgnore(dataPermission = "true")
    List<String> selectAllStorageKeys();

    /** 按 id 升序批量加行锁：删除与"提交时关联附件"靠它串行化（固定锁序，不产生 ABBA）。 */
    @Select("""
        <script>
        SELECT * FROM t_mobile_file
        WHERE id IN <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id
        FOR UPDATE
        </script>
        """)
    @InterceptorIgnore(dataPermission = "true")
    List<MobileFile> selectByIdsForUpdate(@Param("ids") List<UUID> ids);

    /**
     * 带着 claim token 把转码结果发布出去：改指针（storage_key/content_type/size/sha256/名字）并置 READY。
     * 返回 0 = 租约已不在自己手上（或行已被删）→ 调用方**必须丢弃自己的结果对象**，不能碰这一行。
     */
    @Update("""
        UPDATE t_mobile_file
        SET storage_key = #{storageKey}, content_type = #{contentType}, size_bytes = #{size},
            sha256 = #{sha256}, original_name = #{originalName},
            status = 'READY', watermark_text = NULL, processing_error = NULL,
            processing_claim_token = NULL, processing_claimed_at = NULL
        WHERE id = #{id} AND status = 'PROCESSING' AND processing_claim_token = #{token}
        """)
    @InterceptorIgnore(dataPermission = "true")
    int publishProcessed(@Param("id") UUID id, @Param("token") UUID token,
                         @Param("storageKey") String storageKey,
                         @Param("contentType") String contentType, @Param("size") long size,
                         @Param("sha256") String sha256, @Param("originalName") String originalName);

    /** 失败收尾（同样按 token 条件）：租约不在自己手上就不该改这一行的状态。 */
    @Update("""
        UPDATE t_mobile_file
        SET status = 'FAILED', processing_error = #{error},
            processing_claim_token = NULL, processing_claimed_at = NULL
        WHERE id = #{id} AND status = 'PROCESSING' AND processing_claim_token = #{token}
        """)
    @InterceptorIgnore(dataPermission = "true")
    int failProcessing(@Param("id") UUID id, @Param("token") UUID token,
                       @Param("error") String error);
}
