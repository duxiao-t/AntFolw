ALTER TABLE t_mobile_file
  ADD COLUMN IF NOT EXISTS watermark_text VARCHAR(256),
  ADD COLUMN IF NOT EXISTS processing_error VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_mobile_file_processing
  ON t_mobile_file(status, created_at)
  WHERE status = 'PROCESSING';
