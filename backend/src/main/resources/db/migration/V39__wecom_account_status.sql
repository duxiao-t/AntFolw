ALTER TABLE t_wecom_user_mapping
    ADD COLUMN wecom_status INTEGER,
    ADD COLUMN directory_present BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN login_enabled_override BOOLEAN;
