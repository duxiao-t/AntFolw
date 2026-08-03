-- V5__process_snapshot.sql
-- Freeze the process tree at instance start time. After this migration, ProcessInstance.process_snapshot
-- holds the exact tree the engine should walk — independent of future edits to t_process_definition.
-- This is the single most important production-readiness fix for "edit-after-publish" safety.

ALTER TABLE t_process_instance ADD COLUMN process_def_version INT;
ALTER TABLE t_process_instance ADD COLUMN process_snapshot JSONB;

-- Backfill existing rows (pre-migration): copy current process_def.process into the snapshot column.
-- Safe to run on rows where proc_def_id is non-null.
UPDATE t_process_instance pi
SET process_def_version = pd.version,
    process_snapshot = pd.process
FROM t_process_definition pd
WHERE pi.proc_def_id = pd.id
  AND pi.process_snapshot IS NULL;

-- New rows MUST be written by the engine in the same transaction as instance creation.
-- We do not enforce NOT NULL here so the migration is non-blocking; service layer asserts non-null.