-- 新增能力码：发起人作废「待修改」申请（软终态 WITHDRAWN）。
--
-- 为什么在迁移里先建 t_permission：t_role_permission.permission_code 对 t_permission(code) 有外键，
-- 而 Flyway 先于 PermissionCatalogSynchronizer（ApplicationRunner）执行；只改目录会在本迁移的
-- 授权语句上撞外键。启动同步器随后会按 PermissionCatalog 再 upsert 一次（幂等）。

INSERT INTO t_permission(code, name, domain, risk_level, admin_only, scopeable, default_scope, sort_order)
VALUES ('workflow:instance:discard', '作废申请', 'workflow', 'HIGH', false, false, NULL, 225)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    risk_level = EXCLUDED.risk_level,
    admin_only = EXCLUDED.admin_only,
    scopeable = EXCLUDED.scopeable,
    default_scope = EXCLUDED.default_scope,
    sort_order = EXCLUDED.sort_order;

-- 授予原本就能「撤回」的角色（作废是撤回后的后续动作，权限面一致；admin 也在其中）。
INSERT INTO t_role_permission(role_id, permission_code)
SELECT role_id, 'workflow:instance:discard'
FROM t_role_permission
WHERE permission_code = 'workflow:instance:withdraw'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- 让受影响用户的授权快照失效（沿用 V23 的写法）。
UPDATE t_user user_row SET authz_version = authz_version + 1
WHERE EXISTS (
    SELECT 1 FROM t_user_role user_role
    JOIN t_role_permission role_permission ON role_permission.role_id = user_role.role_id
    WHERE user_role.user_id = user_row.id
      AND role_permission.permission_code = 'workflow:instance:discard'
);
