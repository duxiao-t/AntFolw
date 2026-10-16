\set ON_ERROR_STOP on
BEGIN READ ONLY;

SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

SELECT 'instance' AS kind, company_id IS NULL AS missing, company_inferred, count(*)
FROM t_process_instance GROUP BY 2, 3
UNION ALL
SELECT 'form_data', company_id IS NULL, company_inferred, count(*)
FROM t_form_data GROUP BY 2, 3 ORDER BY 1, 2, 3;

SELECT child.id, child.company_id, parent.id AS parent_id, parent.company_id AS parent_company
FROM t_department child JOIN t_department parent ON parent.id = child.parent_id
WHERE child.company_id <> parent.company_id;

SELECT engine_version, status, count(*) FROM t_process_instance GROUP BY 1, 2 ORDER BY 1, 2;

SELECT status, count(*), min(created_at) AS oldest
FROM t_workflow_outbox GROUP BY status ORDER BY status;

SELECT id, company_id, status, phase, lease_expires_at
FROM t_wecom_sync_job WHERE status IN ('PENDING', 'RUNNING') ORDER BY id;

SELECT code, scopeable, default_scope
FROM t_permission WHERE code = 'workflow:outbox:retry';

SELECT r.code AS role_code, rp.scope_override
FROM t_role_permission rp JOIN t_role r ON r.id = rp.role_id
WHERE rp.permission_code = 'workflow:outbox:retry' ORDER BY r.code;

-- Existing BI access is global. This does not authorize company administrators to use this account.
SELECT rolname, rolsuper, rolcreaterole, rolbypassrls
FROM pg_roles WHERE rolname = 'antflow_bi';
COMMIT;
