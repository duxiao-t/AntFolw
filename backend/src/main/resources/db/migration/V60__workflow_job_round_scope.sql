-- 自动化作业（DELAY/TRIGGER）的幂等键纳入「轮次」。
--
-- 背景：作业幂等键原本只有 (proc_inst_id, node_id, job_type)，跨轮次复用，导致两个缺陷：
--   1) 撤回时 cancelActive 把作业置 CANCELLED；重提（轮次 +1）后引擎重新落到同一节点，
--      findNodeJob 命中那条已取消的旧行 → DelayHandler/TriggerHandler 看到非 SUCCEEDED 即
--      `halt` 且不建任务 → 实例停在 RUNNING：无待办、无作业、无恢复入口（retryFailed 只认 FAILED）。
--   2) 上一轮已 SUCCEEDED 的作业会让本轮的延时/回调被静默跳过（每轮都该跑的动作丢了一次）。
--
-- 修法：把 round_no 纳入幂等键，并把唯一索引放宽到「只约束存活状态」——
-- 被取消/失败的历史行保留可追溯，重建或新轮次另起一行，不再互相顶掉。
ALTER TABLE t_workflow_job ADD COLUMN IF NOT EXISTS round_no INT NOT NULL DEFAULT 1;

-- 存量行按各自实例当前轮次回填（历史实例多为 1；已在多轮的实例取真实轮次，
-- 避免回填后与新建作业撞唯一键）。
UPDATE t_workflow_job job
SET round_no = instance.round_no
FROM t_process_instance instance
WHERE instance.id = job.proc_inst_id;

ALTER TABLE t_workflow_job
    ADD CONSTRAINT ck_workflow_job_round CHECK (round_no > 0);

DROP INDEX IF EXISTS ux_workflow_node_job;
CREATE UNIQUE INDEX ux_workflow_node_round_job
    ON t_workflow_job(proc_inst_id, node_id, round_no, job_type)
    WHERE job_type IN ('DELAY', 'TRIGGER') AND status IN ('SCHEDULED', 'RUNNING');
