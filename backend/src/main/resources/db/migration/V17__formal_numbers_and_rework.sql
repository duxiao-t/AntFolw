CREATE SEQUENCE seq_employee_no MINVALUE 1 MAXVALUE 999999 NO CYCLE;
CREATE SEQUENCE seq_business_no MINVALUE 1 MAXVALUE 999999999999 NO CYCLE;

ALTER TABLE t_user ADD COLUMN employee_no VARCHAR(6);
UPDATE t_user SET employee_no = lpad(id::text, 6, '0');
ALTER TABLE t_user ALTER COLUMN employee_no SET NOT NULL;
ALTER TABLE t_user ADD CONSTRAINT ck_user_employee_no CHECK (employee_no ~ '^[0-9]{6}$');
ALTER TABLE t_user ADD CONSTRAINT uk_user_employee_no UNIQUE (employee_no);

ALTER TABLE t_form_data ADD COLUMN business_no VARCHAR(12);
UPDATE t_form_data data
SET business_no = lpad(data.id::text, 12, '0')
WHERE data.status <> 'DRAFT'
   OR EXISTS (SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = data.id);
ALTER TABLE t_form_data ADD CONSTRAINT ck_form_data_business_no
    CHECK (business_no IS NULL OR business_no ~ '^[0-9]{12}$');
ALTER TABLE t_form_data ADD CONSTRAINT uk_form_data_business_no UNIQUE (business_no);

ALTER TABLE t_task ADD COLUMN task_type VARCHAR(16) NOT NULL DEFAULT 'APPROVAL';
ALTER TABLE t_task ADD CONSTRAINT ck_task_type CHECK (task_type IN ('APPROVAL', 'REWORK'));

DO $$
DECLARE
    max_employee_no BIGINT;
    max_business_no BIGINT;
BEGIN
    SELECT max(employee_no::BIGINT) INTO max_employee_no FROM t_user;
    IF max_employee_no IS NULL THEN
        PERFORM setval('seq_employee_no', 1, false);
    ELSE
        PERFORM setval('seq_employee_no', max_employee_no, true);
    END IF;

    SELECT max(business_no::BIGINT) INTO max_business_no FROM t_form_data;
    IF max_business_no IS NULL THEN
        PERFORM setval('seq_business_no', 1, false);
    ELSE
        PERFORM setval('seq_business_no', max_business_no, true);
    END IF;
END $$;

CREATE TEMP TABLE rejected_instance_repair ON COMMIT DROP AS
SELECT pi.id AS instance_id,
       pi.form_data_id,
       pi.started_by,
       rejected.id AS rejected_task_id,
       rejected.approved_at AS rejected_at,
       previous.node_id AS previous_node_id,
       previous.assignee_id AS previous_assignee_id,
       previous.approval_mode AS previous_approval_mode
FROM t_process_instance pi
JOIN LATERAL (
    SELECT t.*
    FROM t_task t
    WHERE t.proc_inst_id = pi.id AND t.status = 'REJECTED'
    ORDER BY t.approved_at DESC NULLS LAST, t.id DESC
    LIMIT 1
) rejected ON true
LEFT JOIN LATERAL (
    SELECT t.*
    FROM t_task t
    WHERE t.proc_inst_id = pi.id
      AND t.status = 'APPROVED'
      AND t.node_id <> rejected.node_id
      AND (rejected.approved_at IS NULL OR t.approved_at < rejected.approved_at)
    ORDER BY t.approved_at DESC NULLS LAST, t.id DESC
    LIMIT 1
) previous ON true
WHERE pi.status = 'REJECTED';

INSERT INTO t_task (proc_inst_id, node_id, assignee_id, status, approval_mode, task_type)
SELECT instance_id,
       COALESCE(previous_node_id, '__rework__'),
       COALESCE(previous_assignee_id, started_by),
       'PENDING',
       COALESCE(previous_approval_mode, 'OR_SIGN'),
       CASE WHEN previous_node_id IS NULL THEN 'REWORK' ELSE 'APPROVAL' END
FROM rejected_instance_repair;

UPDATE t_process_instance pi
SET status = 'RUNNING',
    current_node_id = COALESCE(repair.previous_node_id, '__rework__'),
    finished_at = NULL,
    version = version + 1
FROM rejected_instance_repair repair
WHERE pi.id = repair.instance_id;

UPDATE t_form_data data
SET status = 'NEEDS_REVISION'
FROM rejected_instance_repair repair
WHERE data.id = repair.form_data_id
  AND repair.previous_node_id IS NULL;

INSERT INTO t_task_history (proc_inst_id, from_node_id, to_node_id, task_id,
                            action, operator_id, comment)
SELECT repair.instance_id,
       rejected.node_id,
       COALESCE(repair.previous_node_id, '__rework__'),
       rejected.id,
       'RETURNED',
       rejected.approved_by,
       '历史驳回流程已恢复'
FROM rejected_instance_repair repair
JOIN t_task rejected ON rejected.id = repair.rejected_task_id;
