-- Repair databases that recorded older local V4/V5 form-management migrations
-- before master introduced process-tree migrations at the same versions.
-- Clean databases already execute V4/V5 and this migration becomes a no-op.

ALTER TABLE t_process_definition
    ADD COLUMN IF NOT EXISTS process JSONB NOT NULL DEFAULT '{}'::jsonb;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 't_process_definition'
          AND column_name = 'nodes'
    ) THEN
        ALTER TABLE t_process_definition ALTER COLUMN nodes DROP NOT NULL;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 't_process_definition'
          AND column_name = 'edges'
    ) THEN
        ALTER TABLE t_process_definition ALTER COLUMN edges DROP NOT NULL;
    END IF;
END $$;

COMMENT ON COLUMN t_process_definition.process IS '钉钉式流程树：ROOT 根，children 单链，CONDITIONS.branchs 分支';

ALTER TABLE t_process_instance
    ADD COLUMN IF NOT EXISTS process_def_version INT,
    ADD COLUMN IF NOT EXISTS process_snapshot JSONB;

UPDATE t_process_instance pi
SET process_def_version = pd.version,
    process_snapshot = pd.process
FROM t_process_definition pd
WHERE pi.proc_def_id = pd.id
  AND pi.process_snapshot IS NULL;
