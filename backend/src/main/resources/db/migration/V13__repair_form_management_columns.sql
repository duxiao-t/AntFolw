-- V13__repair_form_management_columns.sql
-- Repair local/dev databases that advanced past V4/V5 before those migrations
-- were finalized. All statements are idempotent for clean databases too.

ALTER TABLE t_form_definition
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE t_form_definition
    DROP CONSTRAINT IF EXISTS t_form_definition_code_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_form_definition_code_active
    ON t_form_definition (code)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS ix_form_definition_status
    ON t_form_definition (status)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS ix_form_definition_name
    ON t_form_definition (name)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS ix_form_data_form_def
    ON t_form_data (form_def_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_form_data_created_by
    ON t_form_data (created_by, created_at DESC);
