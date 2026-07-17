-- V6__task_tree.sql
-- Sprint 2 schema changes for sub-task features: 转交 (TO_ADMIN), 委托 (delegate),
-- 加签 (assign to additional reviewers), CONCURRENTS/DELAY node kinds.
--
-- 设计要点：
-- - parent_task_id: 子任务的父任务 id（加签/转交产生的 sub-task）
-- - delegated_from: 委托来源 user_id（forwardee 实际审批，原始 assignee 仅审计）
-- - is_additional: TRUE 表示"加签"产生的任务（与原始任务互为 OR/AND）
--
-- 设计取舍：
-- - 不引入"task tree path"列 —— 通过 parent_task_id 递归查询即可，单层深度足够
-- - 不强制 NOT NULL —— 兼容已有 8 条 task 记录；新任务才填这 3 个字段

ALTER TABLE t_task ADD COLUMN parent_task_id BIGINT REFERENCES t_task(id);
ALTER TABLE t_task ADD COLUMN delegated_from BIGINT REFERENCES t_user(id);
ALTER TABLE t_task ADD COLUMN is_additional BOOLEAN NOT NULL DEFAULT FALSE;

-- 加索引：按 parent_task_id 查子任务是常用路径
CREATE INDEX idx_task_parent ON t_task(parent_task_id);
-- 加索引：按 delegated_from 查"谁委托给我的"是 inbox 重要过滤
CREATE INDEX idx_task_delegated_from ON t_task(delegated_from);