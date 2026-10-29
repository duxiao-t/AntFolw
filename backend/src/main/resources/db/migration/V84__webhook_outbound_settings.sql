-- Webhook 出站允许主机：从纯 env 升级为「env 基线 + 后台可追加」。
--   * t_webhook_setting 存后台追加的域名（单例行 id=1），私网/回环黑名单不受本表影响。
--   * 新增能力码 integration:webhook:manage（仅管理员），并挂一个「系统设置 → Webhook 出站」菜单。

CREATE TABLE IF NOT EXISTS t_webhook_setting (
    id            INT PRIMARY KEY,
    allowed_hosts TEXT NOT NULL DEFAULT '',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    BIGINT
);
INSERT INTO t_webhook_setting(id) VALUES (1) ON CONFLICT DO NOTHING;

INSERT INTO t_permission(code, name, domain, risk_level, admin_only, scopeable, default_scope, sort_order)
VALUES ('integration:webhook:manage', '管理 Webhook 出站配置', 'integration', 'HIGH', true, false, NULL, 355)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    risk_level = EXCLUDED.risk_level,
    admin_only = EXCLUDED.admin_only;

INSERT INTO t_role_permission(role_id, permission_code)
SELECT id, 'integration:webhook:manage' FROM t_role WHERE code = 'admin'
ON CONFLICT (role_id, permission_code) DO NOTHING;

INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                   required_permissions, sort_order)
SELECT dir.id, 'PAGE', 'settings.webhook', 'Webhook 出站', 'export',
       '["integration:webhook:manage"]'::jsonb, 60
FROM t_menu dir
WHERE dir.type = 'DIR' AND dir.name_override = '系统设置'
  AND NOT EXISTS (SELECT 1 FROM t_menu existing WHERE existing.page_key = 'settings.webhook')
LIMIT 1;

UPDATE t_user SET authz_version = authz_version + 1;
