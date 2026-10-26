-- 回填「有主体授权、却没有公司门槛」的表单。
--
-- 背景：COMPANY 是使用门槛（D-20261014-company-form-use-is-a-gate），后端 hasFormGrant 里
-- 主体匹配之外还有一个 EXISTS(company_grant)。V62 只给当时已存在的表单补了公司，
-- 之后新建的表单在「使用范围」里只选了人员/角色/部门、没选公司时，公司行为 0 条，
-- 于是所有主体授权静默失效：除全局管理员外谁都看不到这张表单，而保存时没有任何提示。
--
-- 这里按最贴近配置意图的信号推导公司，只在「该表单没有任何公司行」时补：
--   1. 部门授权 → 该部门所属公司（含下级部门，因为它们同属一家公司）
--   2. 人员授权 → 该人员所在部门的公司
-- 角色授权**不推导**：同一个角色可能横跨多家公司，按角色回填会把表单意外开放给别的公司。
-- 这类表单保持原状（仅全局管理员可用），由管理员在界面上显式选公司——前端已加拦截与警示。
--
-- 幂等：ON CONFLICT DO NOTHING + NOT EXISTS 双重保护，重复执行无副作用。

INSERT INTO t_form_resource_grant (form_def_id, subject_type, subject_id)
SELECT DISTINCT grant_row.form_def_id, 'COMPANY', department.company_id
FROM t_form_resource_grant grant_row
JOIN t_department department ON department.id = grant_row.subject_id
WHERE grant_row.subject_type = 'DEPARTMENT'
  AND department.company_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM t_form_resource_grant company_grant
      WHERE company_grant.form_def_id = grant_row.form_def_id
        AND company_grant.subject_type = 'COMPANY')
ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING;

INSERT INTO t_form_resource_grant (form_def_id, subject_type, subject_id)
SELECT DISTINCT grant_row.form_def_id, 'COMPANY', department.company_id
FROM t_form_resource_grant grant_row
JOIN t_user user_row ON user_row.id = grant_row.subject_id
JOIN t_department department ON department.id = user_row.dept_id
WHERE grant_row.subject_type = 'USER'
  AND department.company_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM t_form_resource_grant company_grant
      WHERE company_grant.form_def_id = grant_row.form_def_id
        AND company_grant.subject_type = 'COMPANY')
ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING;
