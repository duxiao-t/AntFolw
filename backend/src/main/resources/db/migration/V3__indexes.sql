-- V3__indexes.sql — Separated from V1 so PG index builds don't lock a freshly-migrated DB.
CREATE INDEX IF NOT EXISTS ix_form_schema  ON t_form_definition  USING GIN (schema jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_form_data    ON t_form_data       USING GIN (data jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_proc_nodes   ON t_process_definition USING GIN (nodes jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_proc_edges   ON t_process_definition USING GIN (edges jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_dept_path    ON t_department      USING GIST (path);
CREATE INDEX IF NOT EXISTS ix_dept_company ON t_department      (company_id);
CREATE INDEX IF NOT EXISTS ix_user_dept    ON t_user            (dept_id);
CREATE INDEX IF NOT EXISTS ix_user_role    ON t_user_role       (role_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_pdef_form_version
    ON t_process_definition (form_def_id, version DESC);
