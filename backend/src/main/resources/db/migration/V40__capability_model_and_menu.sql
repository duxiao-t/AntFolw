-- 权限模型 v2：能力点收敛为「域:资源:动作」，页面不再是权限点，数据范围按「角色 × 能力」解析，
-- 导航改为可编排菜单（只存 pageKey 与所需能力，不存组件路径）。
--
-- 本迁移是最后一次在 SQL 里写能力点定义；此后新增能力只改
-- com.antflow.authz.PermissionCatalog，由启动同步器写入。

-- 1) 表结构
ALTER TABLE t_permission
    ADD COLUMN IF NOT EXISTS domain VARCHAR(32),
    ADD COLUMN IF NOT EXISTS scopeable BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS default_scope VARCHAR(40),
    ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 旧列必须先删：category 是 NOT NULL，留着会让下面的能力点插入失败
ALTER TABLE t_permission DROP COLUMN IF EXISTS kind;
ALTER TABLE t_permission DROP COLUMN IF EXISTS category;

ALTER TABLE t_role_permission ADD COLUMN IF NOT EXISTS scope_override VARCHAR(40);
ALTER TABLE t_role_permission DROP CONSTRAINT IF EXISTS ck_role_permission_scope;
ALTER TABLE t_role_permission ADD CONSTRAINT ck_role_permission_scope CHECK (
    scope_override IS NULL OR scope_override IN (
        'SELF', 'DEPARTMENT', 'DEPARTMENT_AND_DESCENDANTS', 'CUSTOM', 'ALL'));

CREATE TABLE IF NOT EXISTS t_role_permission_department (
    role_id          BIGINT NOT NULL REFERENCES t_role(id) ON DELETE CASCADE,
    permission_code  VARCHAR(96) NOT NULL REFERENCES t_permission(code) ON DELETE CASCADE,
    department_id    BIGINT NOT NULL REFERENCES t_department(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code, department_id)
);

-- 2) 能力点目录（与 PermissionCatalog 对齐，启动同步器以代码为准覆盖元数据）
INSERT INTO t_permission
    (code, name, domain, risk_level, admin_only, scopeable, default_scope, sort_order)
VALUES
    ('console:entry:access', '进入管理端', 'console', 'HIGH', false, false, NULL, 10),
    ('org:company:read', '查看企业信息', 'org', 'NORMAL', false, false, NULL, 20),
    ('org:department:read', '查看部门', 'org', 'NORMAL', false, true, 'DEPARTMENT_AND_DESCENDANTS', 30),
    ('org:department:manage', '管理部门', 'org', 'HIGH', false, true, 'DEPARTMENT', 40),
    ('org:user:read', '查看用户', 'org', 'NORMAL', false, true, 'DEPARTMENT_AND_DESCENDANTS', 50),
    ('org:user:manage', '管理用户', 'org', 'HIGH', false, true, 'DEPARTMENT', 60),
    ('org:user_credentials:manage', '重置密码与登录控制', 'org', 'CRITICAL', true, false, NULL, 70),
    ('form:definition:read', '查看表单', 'form', 'NORMAL', false, true, 'ALL', 80),
    ('form:definition:manage', '创建与设计表单', 'form', 'HIGH', false, true, 'ALL', 90),
    ('form:definition:publish', '发布与停用表单', 'form', 'HIGH', false, true, 'ALL', 100),
    ('form:definition:delete', '删除表单', 'form', 'CRITICAL', false, true, 'ALL', 110),
    ('form:authorization:manage', '管理表单使用授权', 'form', 'HIGH', false, false, NULL, 120),
    ('form:runtime:read', '使用已发布表单', 'form', 'NORMAL', false, false, NULL, 130),
    ('form:data:read', '查看表单数据', 'form', 'HIGH', false, true, 'SELF', 140),
    ('form:data:export', '导出表单数据', 'form', 'HIGH', false, true, 'SELF', 150),
    ('workflow:definition:read', '查看流程配置', 'workflow', 'NORMAL', false, true, 'ALL', 160),
    ('workflow:definition:manage', '设计流程配置', 'workflow', 'HIGH', false, true, 'ALL', 170),
    ('workflow:definition:publish', '发布流程配置', 'workflow', 'HIGH', false, true, 'ALL', 180),
    ('workflow:definition:delete', '删除流程配置', 'workflow', 'CRITICAL', false, true, 'ALL', 190),
    ('workflow:instance:start', '发起审批', 'workflow', 'NORMAL', false, false, NULL, 200),
    ('workflow:instance:read', '查看审批记录', 'workflow', 'NORMAL', false, true, 'SELF', 210),
    ('workflow:instance:withdraw', '撤回审批', 'workflow', 'HIGH', false, false, NULL, 220),
    ('workflow:instance:override', '紧急干预审批', 'workflow', 'CRITICAL', false, true, 'ALL', 230),
    ('workflow:monitor:read', '查看流程监控', 'workflow', 'HIGH', false, true, 'ALL', 240),
    ('workflow:task:read', '查看本人任务', 'workflow', 'NORMAL', false, false, NULL, 250),
    ('workflow:task:approve', '审批任务', 'workflow', 'HIGH', false, false, NULL, 260),
    ('workflow:task:reject', '驳回任务', 'workflow', 'HIGH', false, false, NULL, 270),
    ('workflow:task:transfer', '转交任务', 'workflow', 'HIGH', false, false, NULL, 280),
    ('workflow:task:delegate', '委托任务', 'workflow', 'HIGH', false, false, NULL, 290),
    ('workflow:task:add_assignee', '加签任务', 'workflow', 'HIGH', false, false, NULL, 300),
    ('workflow:task:recall', '追回任务', 'workflow', 'HIGH', false, false, NULL, 310),
    ('workflow:automation:retry', '重试流程自动化', 'workflow', 'HIGH', false, true, 'ALL', 320),
    ('integration:wecom:manage', '管理企业微信对接', 'integration', 'HIGH', false, false, NULL, 330),
    ('integration:identity_provider:manage', '管理身份提供方', 'integration', 'HIGH', false, false, NULL, 340),
    ('integration:storage:manage', '管理对象存储', 'integration', 'HIGH', false, false, NULL, 350),
    ('audit:event:read', '查看操作日志', 'audit', 'HIGH', false, false, NULL, 360),
    ('audit:event:export', '导出操作日志', 'audit', 'HIGH', false, false, NULL, 370),
    ('audit:archive:download', '下载日志归档', 'audit', 'CRITICAL', false, false, NULL, 380),
    ('security:permission:read', '查看权限目录', 'security', 'NORMAL', false, false, NULL, 390),
    ('security:role:read', '查看角色', 'security', 'NORMAL', false, false, NULL, 400),
    ('security:role:manage', '管理角色', 'security', 'HIGH', true, false, NULL, 410),
    ('security:user_role:read', '查看用户权限', 'security', 'HIGH', true, false, NULL, 420),
    ('security:user_role:manage', '管理用户权限', 'security', 'CRITICAL', true, false, NULL, 430),
    ('security:effective:read', '预览有效权限', 'security', 'NORMAL', false, false, NULL, 440),
    ('security:menu:manage', '管理导航菜单', 'security', 'HIGH', false, false, NULL, 450),
    ('system:company:manage', '管理企业信息', 'system', 'HIGH', false, false, NULL, 460),
    ('system:backup:manage', '管理系统备份', 'system', 'CRITICAL', true, false, NULL, 470),
    ('file:attachment:read', '读取附件', 'file', 'NORMAL', false, false, NULL, 480),
    ('file:attachment:upload', '上传附件', 'file', 'NORMAL', false, false, NULL, 490)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    risk_level = EXCLUDED.risk_level,
    admin_only = EXCLUDED.admin_only,
    scopeable = EXCLUDED.scopeable,
    default_scope = EXCLUDED.default_scope,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();

-- 3) 旧码 → 新能力映射（含一对多：漏一条就丢能力）
CREATE TEMP TABLE rbac_v2_map (old_code TEXT NOT NULL, new_code TEXT NOT NULL) ON COMMIT DROP;

INSERT INTO rbac_v2_map (old_code, new_code) VALUES
    ('security.permission.read', 'security:permission:read'),
    ('security.role.read', 'security:role:read'),
    ('security.role.write', 'security:role:manage'),
    ('security.user_role.read', 'security:user_role:read'),
    ('security.user_role.write', 'security:user_role:manage'),
    ('security.effective.read', 'security:effective:read'),
    ('security.audit.read', 'audit:event:read'),
    ('security.audit.export', 'audit:event:export'),
    ('security.audit.archive.download', 'audit:archive:download'),
    ('org.company.manage', 'system:company:manage'),
    ('org.company.manage', 'integration:wecom:manage'),
    ('org.company.manage', 'org:company:read'),
    ('org.department.read', 'org:department:read'),
    ('org.department.write', 'org:department:manage'),
    ('org.user.read', 'org:user:read'),
    ('org.user.write', 'org:user:manage'),
    ('form.definition.read', 'form:definition:read'),
    ('form.definition.read', 'workflow:definition:read'),
    ('form.definition.create', 'form:definition:manage'),
    ('form.definition.design', 'form:definition:manage'),
    ('form.definition.design', 'workflow:definition:manage'),
    ('form.definition.publish', 'form:definition:publish'),
    ('form.definition.publish', 'workflow:definition:publish'),
    ('form.definition.delete', 'form:definition:delete'),
    ('form.definition.delete', 'workflow:definition:delete'),
    ('form.authorization.manage', 'form:authorization:manage'),
    ('form.runtime.read', 'form:runtime:read'),
    ('form.data.read', 'form:data:read'),
    ('form.data.export', 'form:data:export'),
    ('workflow.instance.start', 'workflow:instance:start'),
    ('workflow.instance.read', 'workflow:instance:read'),
    ('workflow.instance.withdraw', 'workflow:instance:withdraw'),
    ('workflow.instance.override', 'workflow:instance:override'),
    ('workflow.instance.override', 'workflow:monitor:read'),
    ('workflow.task.read', 'workflow:task:read'),
    ('workflow.task.approve', 'workflow:task:approve'),
    ('workflow.task.reject', 'workflow:task:reject'),
    ('workflow.task.transfer', 'workflow:task:transfer'),
    ('workflow.task.delegate', 'workflow:task:delegate'),
    ('workflow.task.add_assignee', 'workflow:task:add_assignee'),
    ('workflow.task.recall', 'workflow:task:recall'),
    ('workflow.automation.retry', 'workflow:automation:retry'),
    ('file.upload', 'file:attachment:upload'),
    ('file.read', 'file:attachment:read'),
    ('system.backup.manage', 'system:backup:manage');

-- 4) 换算授权：数据范围仅在「能力可配范围 且 旧角色范围 ≠ 新默认」时写覆盖值
INSERT INTO t_role_permission (role_id, permission_code, scope_override)
SELECT rp.role_id, mapped.new_code,
       CASE WHEN permission.scopeable
                 AND permission.default_scope IS DISTINCT FROM role.data_scope
            THEN role.data_scope ELSE NULL END
FROM t_role_permission rp
JOIN rbac_v2_map mapped ON mapped.old_code = rp.permission_code
JOIN t_role role ON role.id = rp.role_id
JOIN t_permission permission ON permission.code = mapped.new_code
ON CONFLICT (role_id, permission_code) DO NOTHING;

INSERT INTO t_role_permission_department (role_id, permission_code, department_id)
SELECT DISTINCT rp.role_id, mapped.new_code, scope_department.department_id
FROM t_role_permission rp
JOIN rbac_v2_map mapped ON mapped.old_code = rp.permission_code
JOIN t_role role ON role.id = rp.role_id
JOIN t_permission permission ON permission.code = mapped.new_code
JOIN t_role_department scope_department ON scope_department.role_id = rp.role_id
WHERE permission.scopeable AND role.data_scope = 'CUSTOM'
ON CONFLICT DO NOTHING;

-- 5) 页面权限持有者：授予入口能力 + 该菜单声明的只读能力集（行为等价迁移）
INSERT INTO t_role_permission (role_id, permission_code, scope_override)
SELECT DISTINCT rp.role_id, 'console:entry:access', NULL
FROM t_role_permission rp
WHERE rp.permission_code LIKE 'page.%'
ON CONFLICT (role_id, permission_code) DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code, scope_override)
SELECT DISTINCT rp.role_id, derived.new_code, NULL
FROM t_role_permission rp
JOIN (VALUES
    ('page.org.contacts', 'org:company:read'),
    ('page.org.contacts', 'org:department:read'),
    ('page.org.contacts', 'org:user:read'),
    ('page.security.roles', 'security:role:read'),
    ('page.security.user_permissions', 'security:user_role:read'),
    ('page.security.audit_log', 'audit:event:read'),
    ('page.approval.forms', 'form:definition:read'),
    ('page.approval.records', 'workflow:instance:read'),
    ('page.report.center', 'form:data:read'),
    ('page.report.dashboard', 'form:data:read'),
    ('page.report.export', 'form:data:export'),
    ('page.settings.company', 'org:company:read'),
    ('page.settings.s3', 'integration:storage:manage'),
    ('page.settings.wecom', 'integration:wecom:manage'),
    ('page.settings.identity_providers', 'integration:identity_provider:manage'),
    ('page.settings.backup', 'system:backup:manage')
) AS derived(page_code, new_code) ON derived.page_code = rp.permission_code
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- 6) 内置角色改名：user → employee
UPDATE t_role SET code = 'employee', name = '员工' WHERE code = 'user';

-- 7) 清理旧码与页面权限（先删授权行，再删能力点，避免外键阻塞）
DELETE FROM t_role_permission WHERE permission_code LIKE 'page.%';
DELETE FROM t_role_permission
WHERE permission_code IN (SELECT old_code FROM rbac_v2_map);
DELETE FROM t_permission WHERE code LIKE 'page.%';
DELETE FROM t_permission WHERE code IN (SELECT old_code FROM rbac_v2_map);

-- 8) 收紧能力点表：域必填、码必须是 域:资源:动作
UPDATE t_permission SET domain = split_part(code, ':', 1) WHERE domain IS NULL;
ALTER TABLE t_permission ALTER COLUMN domain SET NOT NULL;
ALTER TABLE t_permission DROP CONSTRAINT IF EXISTS ck_permission_code_format;
ALTER TABLE t_permission ADD CONSTRAINT ck_permission_code_format
    CHECK (code ~ '^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$');
CREATE INDEX IF NOT EXISTS ix_permission_domain ON t_permission(domain, sort_order);

-- 9) 数据范围不再挂在角色上
ALTER TABLE t_role DROP COLUMN IF EXISTS data_scope;
DROP TABLE IF EXISTS t_role_department;

-- 10) 导航菜单：只编排已注册页面，不存组件路径
CREATE TABLE IF NOT EXISTS t_menu (
    id                   BIGSERIAL PRIMARY KEY,
    parent_id            BIGINT REFERENCES t_menu(id) ON DELETE CASCADE,
    type                 VARCHAR(8) NOT NULL,
    page_key             VARCHAR(96),
    name_override        VARCHAR(128),
    icon_override        VARCHAR(64),
    required_permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    sort_order           INT NOT NULL DEFAULT 0,
    visible              BOOLEAN NOT NULL DEFAULT true,
    version              INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_menu_type CHECK (type IN ('DIR', 'PAGE')),
    CONSTRAINT ck_menu_page_key CHECK (
        (type = 'DIR' AND page_key IS NULL) OR (type = 'PAGE' AND page_key IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_menu_page_key ON t_menu(page_key) WHERE page_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_menu_parent ON t_menu(parent_id, sort_order);

DO $$
DECLARE
    org_dir BIGINT;
    approval_dir BIGINT;
    report_dir BIGINT;
    security_dir BIGINT;
    settings_dir BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM t_menu) THEN
        RETURN;
    END IF;

    INSERT INTO t_menu(type, page_key, name_override, icon_override, required_permissions, sort_order)
    VALUES ('PAGE', 'workplace', '工作台', 'home', '[]'::jsonb, 10);

    INSERT INTO t_menu(type, name_override, icon_override, sort_order)
    VALUES ('DIR', '组织架构', 'team', 20) RETURNING id INTO org_dir;
    INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                       required_permissions, sort_order)
    VALUES (org_dir, 'PAGE', 'org.contacts', '通讯录', 'contacts',
            '["org:company:read","org:department:read","org:user:read"]'::jsonb, 10);

    INSERT INTO t_menu(type, name_override, icon_override, sort_order)
    VALUES ('DIR', '审批与流程', 'audit', 30) RETURNING id INTO approval_dir;
    INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                       required_permissions, sort_order)
    VALUES
        (approval_dir, 'PAGE', 'approval.forms', '表单管理', 'form',
         '["form:definition:read"]'::jsonb, 10),
        (approval_dir, 'PAGE', 'approval.records', '审批记录', 'search',
         '["workflow:instance:read"]'::jsonb, 20),
        (approval_dir, 'PAGE', 'approval.monitor', '流程监控', 'dashboard',
         '["workflow:monitor:read"]'::jsonb, 30);

    INSERT INTO t_menu(type, name_override, icon_override, sort_order)
    VALUES ('DIR', '数据与报表', 'barChart', 40) RETURNING id INTO report_dir;
    INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                       required_permissions, sort_order)
    VALUES
        (report_dir, 'PAGE', 'report.center', '报表中心', 'fund',
         '["form:data:read"]'::jsonb, 10),
        (report_dir, 'PAGE', 'report.view', '数据看板', 'dashboard',
         '["form:data:read"]'::jsonb, 20),
        (report_dir, 'PAGE', 'report.export', '数据导出', 'export',
         '["form:data:export"]'::jsonb, 30);

    INSERT INTO t_menu(type, name_override, icon_override, sort_order)
    VALUES ('DIR', '权限与安全', 'safetyCertificate', 50) RETURNING id INTO security_dir;
    INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                       required_permissions, sort_order)
    VALUES
        (security_dir, 'PAGE', 'security.roles', '角色管理', 'idcard',
         '["security:role:read"]'::jsonb, 10),
        (security_dir, 'PAGE', 'security.user-permissions', '用户权限分配', 'key',
         '["security:user_role:read"]'::jsonb, 20),
        (security_dir, 'PAGE', 'security.audit-log', '操作日志审计', 'fileSearch',
         '["audit:event:read"]'::jsonb, 30),
        (security_dir, 'PAGE', 'security.menu', '菜单管理', 'menu',
         '["security:menu:manage"]'::jsonb, 40);

    INSERT INTO t_menu(type, name_override, icon_override, sort_order)
    VALUES ('DIR', '系统设置', 'setting', 60) RETURNING id INTO settings_dir;
    INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                       required_permissions, sort_order)
    VALUES
        (settings_dir, 'PAGE', 'settings.company', '企业基础信息', 'bank',
         '["org:company:read"]'::jsonb, 10),
        (settings_dir, 'PAGE', 'settings.s3', 'S3 存储', 'cloud',
         '["integration:storage:manage"]'::jsonb, 20),
        (settings_dir, 'PAGE', 'settings.wecom', '企业微信', 'wechat',
         '["integration:wecom:manage"]'::jsonb, 30),
        (settings_dir, 'PAGE', 'settings.identity-providers', '身份提供方', 'safetyCertificate',
         '["integration:identity_provider:manage"]'::jsonb, 40),
        (settings_dir, 'PAGE', 'settings.backup', '系统备份', 'database',
         '["system:backup:manage"]'::jsonb, 50);
END $$;

-- 11) 全量失效权限快照
UPDATE t_user SET authz_version = authz_version + 1;
