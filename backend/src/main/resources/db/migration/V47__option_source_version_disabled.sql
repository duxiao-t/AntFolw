-- 版本级「停用」（下架）：有时间戳 = 这一版不再被「新绑定」挑到。
--
-- 状态仍是 PUBLISHED，所以钉着它的表单在填写时读候选完全不受影响
-- （requireVersion 的读路径要求 status='PUBLISHED'）。
-- 这就是「停用」和「取消发布」的区别：停用只收窄候选，取消发布会让在用它的表单 422。
--
-- 不往 ck_option_source_version_status 里塞第三个值：DRAFT/PUBLISHED 是生命周期，
-- 下架是叠在「已发布」之上的状态，加列比改约束安全。
ALTER TABLE t_option_data_source_version
    ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ;
