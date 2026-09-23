-- 第三方 BI 的只读账号：只授 V45 的台账视图，不授任何基表。
--
-- 刻意不进 Flyway 迁移：建角色需要 elevated 权限（应用账号不一定有），
-- 而且密码不能进 git。由运维在库外执行本脚本，然后单独设置密码：
--
--   psql -v ON_ERROR_STOP=1 -f infra/sql/bi-readonly-role.sql
--   psql -c "ALTER ROLE antflow_bi PASSWORD '<在库外生成，存进你们的密钥管理>'"
--
-- 幂等：可重复执行。

-- 出错就停。下面几条 RAISE 是安全自查，默认「出错继续」会让它们形同虚设：
-- 报完错照样往下 REVOKE/GRANT，看起来像成功了。
\set ON_ERROR_STOP on

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'antflow_bi') THEN
        CREATE ROLE antflow_bi LOGIN;
    END IF;
END $$;

-- 自查一：复用的角色本身不能带高权限。表级 REVOKE 挡不住这几个，否则这个账号就不是
-- 「只读视图账号」了。
DO $$
DECLARE role_flags text;
BEGIN
    SELECT concat_ws(', ',
             CASE WHEN rolsuper THEN 'SUPERUSER' END,
             CASE WHEN rolbypassrls THEN 'BYPASSRLS' END,
             CASE WHEN rolcreaterole THEN 'CREATEROLE' END)
    INTO role_flags
    FROM pg_roles WHERE rolname = 'antflow_bi';
    IF coalesce(role_flags, '') <> '' THEN
        RAISE EXCEPTION 'antflow_bi has elevated attributes (%); drop them before provisioning BI',
            role_flags;
    END IF;
END $$;

-- 自查二：不能靠成员角色继承权限——继承来的基表读用 `REVOKE ... FROM antflow_bi` 是撤不掉的。
DO $$
DECLARE inherited_role name;
BEGIN
    SELECT parent.rolname INTO inherited_role
    FROM pg_auth_members membership
    JOIN pg_roles member ON member.oid = membership.member
    JOIN pg_roles parent ON parent.oid = membership.roleid
    WHERE member.rolname = 'antflow_bi'
    LIMIT 1;
    IF inherited_role IS NOT NULL THEN
        RAISE EXCEPTION 'antflow_bi inherits privileges from role %; remove membership first',
            inherited_role;
    END IF;
END $$;

-- 先扫掉 public 下所有表/视图的授权（含别人手抖加的 `GRANT ON ALL TABLES`），再只授视图。
-- 顺序不能反：ALL TABLES 也覆盖视图，放在 GRANT 之后会把下面刚授的 SELECT 又收走。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM antflow_bi;
-- 单独收 PUBLIC：`REVOKE ... FROM antflow_bi` 只动"直接授予 antflow_bi"的权限，撤不掉经
-- PUBLIC 继承的那份——不收掉的话任何角色（含 BI）都能顺着 PUBLIC 读基表，等于白做。
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- 自查三（后置）：撤完之后 public 下不该再有任何对 PUBLIC 开放的读权限。
-- 用 pg_class.relacl / pg_namespace.nspacl 而不是 has_table_privilege('PUBLIC', …)：
-- PUBLIC 是 oid=0 的授权伪角色，不是 pg_roles 里的角色名，传进去会直接报
-- `role "PUBLIC" does not exist`（整个 DO 块中止）。relacl/nspacl 为 NULL 表示没有显式授权。
DO $$
DECLARE exposed text;
BEGIN
    SELECT format('%I.%I', n.nspname, c.relname) INTO exposed
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm', 'f')   -- 表 / 分区表 / 视图 / 物化视图 / 外部表
      AND EXISTS (SELECT 1 FROM aclexplode(c.relacl) a
                  WHERE a.grantee = 0 AND a.privilege_type = 'SELECT')
    LIMIT 1;
    IF exposed IS NOT NULL THEN
        RAISE EXCEPTION 'PUBLIC can still SELECT %; revoke it before provisioning BI', exposed;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace n, aclexplode(n.nspacl) a
               WHERE n.nspname = 'public' AND a.grantee = 0 AND a.privilege_type = 'CREATE') THEN
        RAISE EXCEPTION 'PUBLIC still has CREATE on schema public; revoke it before provisioning BI';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO antflow_bi;

GRANT SELECT ON v_form_ledger, v_form_field_catalog, v_form_option_catalog,
                v_user_catalog, v_department_catalog TO antflow_bi;
