-- Parallel gateway support (v1):
-- mark each task with the owning parallel gateway node and branch so the engine
-- can wait for all branches before joining.
ALTER TABLE t_task ADD COLUMN IF NOT EXISTS parallel_id VARCHAR(64);
ALTER TABLE t_task ADD COLUMN IF NOT EXISTS branch_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_task_parallel_gateway
    ON t_task(proc_inst_id, parallel_id, status);
