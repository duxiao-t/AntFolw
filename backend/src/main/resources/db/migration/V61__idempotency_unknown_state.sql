-- 幂等记录增加 UNKNOWN 态：业务已执行、但结果对客户端不可知时，不允许原样重放。
--
-- 背景（见 docs/code-review-2026-10-17.md A2 第三条）：过滤器原来把"处理链抛异常"与
-- "业务明确失败"都标成 FAILED，而 tryClaim 允许 FAILED + 同 body 重新抢占 —— 如果异常发生在
-- 事务提交之后（或后续过滤器），客户端重试就会**再执行一次副作用**（重复发起/重复审批）。
--
-- UNKNOWN 的语义：请求可能已生效，服务端拒绝重放；客户端应改为查询结果而不是重试。
ALTER TABLE t_idempotency_record DROP CONSTRAINT IF EXISTS ck_idempotency_status;
ALTER TABLE t_idempotency_record
    ADD CONSTRAINT ck_idempotency_status
    CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'));
