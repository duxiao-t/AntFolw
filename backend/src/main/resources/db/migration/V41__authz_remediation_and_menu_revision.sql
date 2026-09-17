-- V40 has shipped to local databases. All authorization remediation is forward-only.

CREATE TABLE t_menu_revision (
    id          SMALLINT PRIMARY KEY,
    version     INT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_menu_revision_singleton CHECK (id = 1)
);

INSERT INTO t_menu_revision(id, version)
SELECT 1, COALESCE(MAX(version), 0) FROM t_menu;

CREATE TEMP TABLE affected_authz_users(user_id BIGINT PRIMARY KEY) ON COMMIT DROP;

INSERT INTO affected_authz_users(user_id)
SELECT DISTINCT user_role.user_id
FROM t_user_role user_role
JOIN t_role role ON role.id = user_role.role_id
JOIN t_role_permission granted ON granted.role_id = role.id
JOIN t_permission permission ON permission.code = granted.permission_code
WHERE role.code <> 'admin'
  AND (permission.admin_only
       OR granted.permission_code IN (
           'integration:storage:manage',
           'integration:identity_provider:manage'));

DELETE FROM t_role_permission granted
USING t_role role, t_permission permission
WHERE granted.role_id = role.id
  AND permission.code = granted.permission_code
  AND role.code <> 'admin'
  AND (permission.admin_only
       OR granted.permission_code IN (
           'integration:storage:manage',
           'integration:identity_provider:manage'));

UPDATE t_user user_row
SET authz_version = authz_version + 1
WHERE user_row.id IN (SELECT user_id FROM affected_authz_users);
