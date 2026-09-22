-- 第三方 BI 的只读账号：只授 V45 的台账视图，不授任何基表。
--
-- 刻意不进 Flyway 迁移：建角色需要 elevated 权限（应用账号不一定有），
-- 而且密码不能进 git。由运维在库外执行本脚本，然后单独设置密码：
--
--   psql -f infra/sql/bi-readonly-role.sql
--   psql -c "ALTER ROLE antflow_bi PASSWORD '<在库外生成，存进你们的密钥管理>'"
--
-- 幂等：可重复执行。

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'antflow_bi') THEN
        CREATE ROLE antflow_bi LOGIN;
    END IF;
END $$;

-- 先扫掉 public 下所有表/视图的授权（含别人手抖加的 `GRANT ON ALL TABLES`），再只授视图。
-- 顺序不能反：ALL TABLES 也覆盖视图，放在 GRANT 之后会把下面刚授的 SELECT 又收走。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM antflow_bi;

GRANT USAGE ON SCHEMA public TO antflow_bi;

GRANT SELECT ON v_form_ledger, v_form_field_catalog, v_form_option_catalog,
                v_user_catalog, v_department_catalog TO antflow_bi;
