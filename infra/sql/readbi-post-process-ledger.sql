-- readbi 的单表单 BI 视图（本地部署专用，不进入 Flyway）。
--
-- 通用台账改名为 v_form_ledger_all，v_form_ledger 本身收窄为单表单视图；
-- readbi 看不到通用台账或基表。
-- 用法：
--   docker exec -i antflow-local-postgres-1 psql -U antflow -d antflow \
--     -v form_code=form_1792485429276 -f - < infra/sql/readbi-post-process-ledger.sql
--
-- 表单 code 改了才需要重跑。若后续迁移重建或调整 v_form_ledger，
-- 本脚本会把迁移刚重建的通用视图接管为 v_form_ledger_all。
\set ON_ERROR_STOP on

\if :{?form_code}
\else
  \set form_code form_1792485429276
\endif

BEGIN;

DROP VIEW IF EXISTS v_form_ledger_checklist;
DROP VIEW IF EXISTS v_form_ledger_checklist_all;

DO $$
DECLARE
  pair record;
  has_view boolean;
  has_all boolean;
  view_is_wrapper boolean;
BEGIN
  FOR pair IN
    SELECT view_name, all_name
    FROM (VALUES
      ('v_form_ledger', 'v_form_ledger_all')
    ) AS views(view_name, all_name)
  LOOP
    has_view := to_regclass(format('public.%I', pair.view_name)) IS NOT NULL;
    has_all := to_regclass(format('public.%I', pair.all_name)) IS NOT NULL;

    IF NOT has_view AND has_all THEN
      NULL; -- 包装视图缺失，下面直接重建。
    ELSIF has_view AND NOT has_all THEN
      EXECUTE format('ALTER VIEW public.%I RENAME TO %I', pair.view_name, pair.all_name);
    ELSIF has_view AND has_all THEN
      SELECT pg_get_viewdef(format('public.%I', pair.view_name)::regclass)
                 LIKE '%' || pair.all_name || '%'
        INTO view_is_wrapper;
      IF view_is_wrapper THEN
        EXECUTE format('DROP VIEW public.%I', pair.view_name);
      ELSE
        -- 迁移刚重建了通用视图；旧 all 已无包装视图依赖，先移除再接管新定义。
        EXECUTE format('DROP VIEW public.%I', pair.all_name);
        EXECUTE format('ALTER VIEW public.%I RENAME TO %I', pair.view_name, pair.all_name);
      END IF;
    ELSE
      RAISE EXCEPTION 'both % and % are missing', pair.view_name, pair.all_name;
    END IF;
  END LOOP;
END $$;

CREATE VIEW v_form_ledger AS
SELECT *
FROM v_form_ledger_all
WHERE form_code = :'form_code';

REVOKE ALL ON v_form_ledger_all FROM readbi;
GRANT SELECT ON v_form_ledger TO readbi;

COMMIT;
