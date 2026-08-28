ALTER TABLE t_task ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_task_assignee_cc_unread
    ON t_task(assignee_id, created_at DESC)
    WHERE status = 'CC' AND read_at IS NULL;
