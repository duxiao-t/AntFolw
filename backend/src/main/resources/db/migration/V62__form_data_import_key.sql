-- 表单数据历史补录：外部业务单号 + 导入批次。
--
-- **为什么不能拿 business_no 当幂等键**：
--   1) business_no 是**系统生成**的（BusinessNumberService），补录时每行都会生成新的，
--      它跟"用户手上那份历史台账的单号"没有任何关系，拿它去重永远命中不了；
--   2) business_no 上有 **全局唯一** 约束（V17/V34），历史补录的外部单号只在业务上
--      "同一张表单内唯一"，不同表单用同一个单号是正常的，塞进 business_no 会直接 23505；
--   3) 补录单据将来若被正式流程接管，business_no 还要重新分配，用它当幂等键等于把
--      一次性的历史标识钉进了系统单号。
--   所以单号单独落 import_key，并按 (form_def_id, company_id) 做**部分唯一索引**：
--   只在有单号的行上生效，正常提交（import_key IS NULL）完全不受影响。
--
-- import_batch 只是一次导入的回溯标记（排查"这批数据是谁什么时候导的"），不参与唯一性。

ALTER TABLE t_form_data
  ADD COLUMN IF NOT EXISTS import_key   VARCHAR(128),
  ADD COLUMN IF NOT EXISTS import_batch VARCHAR(40);

-- 部分索引：只约束"真的带了外部单号"的导入行。NULL 不进入索引，
-- 因此存量数据与普通提交不需要任何回填、也不会互相冲突。
CREATE UNIQUE INDEX IF NOT EXISTS uk_form_data_import_key
  ON t_form_data(form_def_id, company_id, import_key) WHERE import_key IS NOT NULL;

COMMENT ON COLUMN t_form_data.import_key IS
  '外部业务单号：仅历史导入写入，作为幂等键；**不是** business_no（后者由系统生成且全局唯一）';
COMMENT ON COLUMN t_form_data.import_batch IS
  '导入批次号：一次导入写入的行共享同一个值，用于回溯；不参与唯一性';
