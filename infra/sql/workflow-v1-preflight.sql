-- Read-only retirement gate. Execute against each release target; an empty local result is not production evidence.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SELECT engine_version, status, current_node_id = '__rework__' AS rework, count(*)
FROM t_process_instance
GROUP BY engine_version, status, current_node_id = '__rework__'
ORDER BY 1, 2, 3;

SELECT instance.id, instance.status, instance.current_node_id,
       (SELECT count(*) FROM t_task task
        WHERE task.proc_inst_id = instance.id AND task.status IN ('PENDING', 'BLOCKED')) AS active_tasks,
       (SELECT count(*) FROM t_workflow_job job
        WHERE job.proc_inst_id = instance.id AND job.status IN ('SCHEDULED', 'RUNNING', 'FAILED')) AS recoverable_jobs
FROM t_process_instance instance
WHERE instance.engine_version = 1
  AND (instance.status = 'RUNNING' OR instance.current_node_id = '__rework__'
    OR EXISTS (SELECT 1 FROM t_task task WHERE task.proc_inst_id = instance.id
      AND task.status IN ('PENDING', 'BLOCKED'))
    OR EXISTS (SELECT 1 FROM t_workflow_job job WHERE job.proc_inst_id = instance.id
      AND job.status IN ('SCHEDULED', 'RUNNING', 'FAILED')))
ORDER BY instance.id;
COMMIT;
