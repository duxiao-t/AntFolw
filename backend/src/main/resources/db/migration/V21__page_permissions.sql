ALTER TABLE t_permission
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'ACTION',
    ADD COLUMN IF NOT EXISTS admin_only BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE t_permission DROP CONSTRAINT IF EXISTS ck_permission_kind;
ALTER TABLE t_permission ADD CONSTRAINT ck_permission_kind
    CHECK (kind IN ('PAGE', 'ACTION'));

INSERT INTO t_permission (code, name, category, risk_level, sort_order, kind, admin_only) VALUES
    ('page.workplace', '工作台', '通用', 'NORMAL', 1, 'PAGE', false),
    ('page.org.contacts', '通讯录', '组织架构', 'NORMAL', 10, 'PAGE', false),
    ('page.security.roles', '角色管理', '权限与安全', 'HIGH', 20, 'PAGE', false),
    ('page.security.user_permissions', '用户权限分配', '权限与安全', 'CRITICAL', 21, 'PAGE', true),
    ('page.security.audit_log', '操作日志审计', '权限与安全', 'HIGH', 22, 'PAGE', false),
    ('page.approval.forms', '表单管理', '审批与流程', 'NORMAL', 30, 'PAGE', false),
    ('page.approval.records', '审批记录查询', '审批与流程', 'NORMAL', 31, 'PAGE', false),
    ('page.report.center', '报表中心', '数据与报表', 'HIGH', 40, 'PAGE', false),
    ('page.report.export', '数据导出', '数据与报表', 'HIGH', 41, 'PAGE', false),
    ('page.report.dashboard', '数据看板', '数据与报表', 'HIGH', 42, 'PAGE', false),
    ('page.settings.company', '企业基础信息', '系统设置', 'HIGH', 50, 'PAGE', false),
    ('page.settings.s3', 'S3 存储', '系统设置', 'HIGH', 51, 'PAGE', false),
    ('page.settings.wecom', '企业微信', '系统设置', 'HIGH', 52, 'PAGE', false),
    ('page.settings.billing', '订阅与账单', '系统设置', 'HIGH', 53, 'PAGE', false)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    risk_level = EXCLUDED.risk_level,
    sort_order = EXCLUDED.sort_order,
    kind = EXCLUDED.kind,
    admin_only = EXCLUDED.admin_only;

UPDATE t_permission SET category = CASE category
    WHEN 'Security' THEN '权限与安全'
    WHEN 'Audit' THEN '权限与安全'
    WHEN 'Organization' THEN '组织架构'
    WHEN 'Forms' THEN '审批与流程'
    WHEN 'Data' THEN '数据与报表'
    WHEN 'Workflow' THEN '审批与流程'
    WHEN 'Files' THEN '文件'
    ELSE category
END
WHERE kind = 'ACTION';

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, permission.code
FROM t_role role CROSS JOIN t_permission permission
WHERE role.code = 'admin' AND permission.kind = 'PAGE'
ON CONFLICT DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, 'page.workplace'
FROM t_role role WHERE role.code = 'user'
ON CONFLICT DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, 'page.security.audit_log'
FROM t_role role WHERE role.code = 'auditor'
ON CONFLICT DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT DISTINCT role.id, mapping.page_code
FROM t_role role
JOIN t_role_permission grant_row ON grant_row.role_id = role.id
JOIN (VALUES
    ('security.role.read', 'page.security.roles'),
    ('security.audit.read', 'page.security.audit_log'),
    ('form.definition.read', 'page.approval.forms'),
    ('workflow.instance.read', 'page.approval.records'),
    ('form.data.read', 'page.report.center'),
    ('form.data.export', 'page.report.export'),
    ('form.data.read', 'page.report.dashboard'),
    ('org.company.manage', 'page.settings.company'),
    ('org.company.manage', 'page.settings.s3'),
    ('org.company.manage', 'page.settings.wecom'),
    ('org.company.manage', 'page.settings.billing'),
    ('form.runtime.read', 'page.workplace'),
    ('workflow.instance.start', 'page.workplace'),
    ('workflow.task.approve', 'page.workplace')
) AS mapping(action_code, page_code) ON mapping.action_code = grant_row.permission_code
WHERE role.code <> 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, 'page.org.contacts'
FROM t_role role
WHERE role.code <> 'admin'
  AND EXISTS (SELECT 1 FROM t_role_permission p WHERE p.role_id = role.id AND p.permission_code = 'org.department.read')
  AND EXISTS (SELECT 1 FROM t_role_permission p WHERE p.role_id = role.id AND p.permission_code = 'org.user.read')
ON CONFLICT DO NOTHING;
