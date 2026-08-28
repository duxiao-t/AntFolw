INSERT INTO t_permission(code, name, category, risk_level, sort_order, kind, admin_only)
VALUES ('workflow.task.read', '查看本人任务', '审批与流程', 'NORMAL', 335, 'ACTION', false)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    risk_level = EXCLUDED.risk_level,
    sort_order = EXCLUDED.sort_order,
    kind = EXCLUDED.kind,
    admin_only = EXCLUDED.admin_only;

INSERT INTO t_role_permission(role_id, permission_code)
SELECT id, 'workflow.task.read' FROM t_role WHERE code = 'admin'
ON CONFLICT DO NOTHING;

WITH task_roles AS (
    SELECT DISTINCT role_id FROM t_role_permission
    WHERE permission_code = 'page.workplace' OR permission_code LIKE 'workflow.task.%'
)
INSERT INTO t_role_permission(role_id, permission_code)
SELECT role_id, 'workflow.task.read' FROM task_roles
ON CONFLICT DO NOTHING;

WITH task_roles AS (
    SELECT DISTINCT role_id FROM t_role_permission
    WHERE permission_code = 'workflow.task.read' OR permission_code LIKE 'workflow.task.%'
)
INSERT INTO t_role_permission(role_id, permission_code)
SELECT role_id, 'page.workplace' FROM task_roles
ON CONFLICT DO NOTHING;

UPDATE t_user user_row SET authz_version = authz_version + 1
WHERE EXISTS (
    SELECT 1 FROM t_user_role user_role
    JOIN t_role_permission role_permission ON role_permission.role_id = user_role.role_id
    WHERE user_role.user_id = user_row.id
      AND (role_permission.permission_code = 'page.workplace'
        OR role_permission.permission_code LIKE 'workflow.task.%')
);
