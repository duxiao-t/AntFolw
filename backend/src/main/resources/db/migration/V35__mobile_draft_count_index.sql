CREATE INDEX IF NOT EXISTS ix_form_data_user_drafts
    ON t_form_data (created_by)
    WHERE status = 'DRAFT';
