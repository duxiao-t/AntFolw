CREATE INDEX IF NOT EXISTS idx_task_history_instance_created
  ON t_task_history(proc_inst_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_process_instance_started_by_started_at
  ON t_process_instance(started_by, started_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_process_instance_status_started_at
  ON t_process_instance(status, started_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_role_permission_permission_role
  ON t_role_permission(permission_code, role_id);
