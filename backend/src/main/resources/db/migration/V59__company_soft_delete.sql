-- 公司改为「停用（软删除）」语义：公司被部门、用户、流程实例与填报记录引用，
-- 硬删会让这些数据失去归属；停用后历史数据仍可读，只是不再出现在列表与选择器里。
-- deleted 沿用 V13 起的 SMALLINT 约定，MyBatis-Plus 全局 logic-delete-field: deleted 自动过滤。
ALTER TABLE t_company
    ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_company_active ON t_company (deleted, id);
