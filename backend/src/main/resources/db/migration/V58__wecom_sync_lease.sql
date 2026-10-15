ALTER TABLE t_wecom_sync_job
    ADD COLUMN claim_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE INDEX ix_wecom_sync_recovery ON t_wecom_sync_job(status, lease_expires_at)
    WHERE status IN ('PENDING', 'RUNNING');
