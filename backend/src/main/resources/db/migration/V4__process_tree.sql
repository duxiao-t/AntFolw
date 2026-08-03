-- V4__process_tree.sql
-- 钉钉式递归树取代 nodes[]+edges[] 平面图。MVP 无历史流程数据，直接加列并弃用旧列。
ALTER TABLE t_process_definition ADD COLUMN process JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE t_process_definition ALTER COLUMN nodes DROP NOT NULL;
ALTER TABLE t_process_definition ALTER COLUMN edges DROP NOT NULL;
COMMENT ON COLUMN t_process_definition.process IS '钉钉式流程树：ROOT 根，children 单链，CONDITIONS.branchs 分支';
