-- 异步视频水印的独占租约。
--
-- 只有 status='PROCESSING' 一件事时，"正在跑"和"孤儿"分不开：启动恢复会把在飞的任务再排一遍，
-- 定时 reaper 也可能把还在跑的抢走——两个 ffmpeg 同时写同一个对象、后写的把先写的结果盖掉。
-- claim token 让"谁拥有这一行"可判定；claimed_at 是租约时间，处理期间由 worker 续租。
--
-- 这两个列**故意不映射到 MobileFile 实体**：实体上的字段会被 updateById 一并回写，
-- 而业务路径的 updateById 不该碰租约（会把别的 worker 的 claim 清掉）。
ALTER TABLE t_mobile_file
    ADD COLUMN IF NOT EXISTS processing_claim_token UUID,
    ADD COLUMN IF NOT EXISTS processing_claimed_at TIMESTAMPTZ;

-- 不加新索引：V27 的 idx_mobile_file_processing (status, created_at) WHERE status='PROCESSING'
-- 已经覆盖 reaper 的「按创建时间找过期的 PROCESSING」。租约时间是过滤条件，不是查找入口。
