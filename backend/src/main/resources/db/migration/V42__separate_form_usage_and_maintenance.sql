-- 表单使用范围与模板维护职责分离。
-- t_form_resource_grant 只控制已发布表单的使用；本表只记录可维护模板的用户。
CREATE TABLE t_form_maintainer (
    form_def_id BIGINT NOT NULL REFERENCES t_form_definition(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES t_user(id),
    granted_by  BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (form_def_id, user_id)
);

CREATE INDEX ix_form_maintainer_user
    ON t_form_maintainer(user_id, form_def_id);

-- 创建人天然是首位维护人。
INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
SELECT form.id, form.created_by, form.created_by
FROM t_form_definition form
JOIN t_user creator ON creator.id = form.created_by AND creator.status = 'ACTIVE'
WHERE form.deleted = 0
ON CONFLICT DO NOTHING;

-- 模板能力只表示动作资格，逐表成员关系替代创建人部门范围；数据类能力保持原有范围。
UPDATE t_permission
SET scopeable = false, default_scope = NULL, updated_at = now()
WHERE code LIKE 'form:definition:%' OR code LIKE 'workflow:definition:%';

UPDATE t_role_permission
SET scope_override = NULL
WHERE permission_code LIKE 'form:definition:%'
   OR permission_code LIKE 'workflow:definition:%';

DELETE FROM t_role_permission_department
WHERE permission_code LIKE 'form:definition:%'
   OR permission_code LIKE 'workflow:definition:%';

UPDATE t_user SET authz_version = authz_version + 1;

-- 兼容存量：仅把“原本可使用该表单且已持有表单/流程维护能力”的用户回填为维护人。
-- 普通使用者不会因为本次迁移获得模板管理资格。
WITH management_users AS (
    SELECT DISTINCT user_role.user_id
    FROM t_user_role user_role
    JOIN t_user user_row ON user_row.id = user_role.user_id
      AND user_row.status = 'ACTIVE'
    JOIN t_role role ON role.id = user_role.role_id AND role.enabled = true
    JOIN t_role_permission role_permission ON role_permission.role_id = role.id
    WHERE role_permission.permission_code IN (
        'form:definition:manage',
        'form:definition:publish',
        'form:definition:delete',
        'workflow:definition:manage',
        'workflow:definition:publish',
        'workflow:definition:delete'
    )
), effective_old_grants AS (
    SELECT DISTINCT form_grant.form_def_id, management_user.user_id, form_grant.granted_by
    FROM t_form_resource_grant form_grant
    JOIN management_users management_user ON
      (form_grant.subject_type = 'USER'
        AND form_grant.subject_id = management_user.user_id)
      OR (form_grant.subject_type = 'ROLE' AND EXISTS (
        SELECT 1
        FROM t_user_role granted_user_role
        JOIN t_role granted_role ON granted_role.id = granted_user_role.role_id
          AND granted_role.enabled = true
        WHERE granted_user_role.user_id = management_user.user_id
          AND granted_user_role.role_id = form_grant.subject_id
      ))
      OR (form_grant.subject_type = 'DEPARTMENT' AND EXISTS (
        SELECT 1
        FROM t_user granted_user
        JOIN t_department user_department ON user_department.id = granted_user.dept_id
        JOIN t_department grant_department ON grant_department.id = form_grant.subject_id
        WHERE granted_user.id = management_user.user_id
          AND user_department.path <@ grant_department.path
      ))
    JOIN t_form_definition form ON form.id = form_grant.form_def_id AND form.deleted = 0
)
INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
SELECT form_def_id, user_id, granted_by
FROM effective_old_grants
ON CONFLICT DO NOTHING;

-- 极旧数据可能没有创建人或创建人已停用；由首个有效管理员兜底，避免空维护名单。
INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
SELECT form.id, fallback_admin.id, fallback_admin.id
FROM t_form_definition form
CROSS JOIN LATERAL (
    SELECT user_row.id
    FROM t_user user_row
    JOIN t_user_role user_role ON user_role.user_id = user_row.id
    JOIN t_role role ON role.id = user_role.role_id
    WHERE user_row.status = 'ACTIVE' AND role.code = 'admin' AND role.enabled = true
    ORDER BY user_row.id
    LIMIT 1
) fallback_admin
WHERE form.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM t_form_maintainer maintainer
    WHERE maintainer.form_def_id = form.id
  )
ON CONFLICT DO NOTHING;
