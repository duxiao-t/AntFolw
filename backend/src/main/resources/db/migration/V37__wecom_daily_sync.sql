ALTER TABLE t_wecom_config
    ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN schedule_time TIME NOT NULL DEFAULT TIME '03:00:00',
    ADD COLUMN schedule_mode VARCHAR(16) NOT NULL DEFAULT 'INCREMENTAL',
    ADD COLUMN schedule_last_run_date DATE,
    ADD CONSTRAINT ck_wecom_schedule_mode
        CHECK (schedule_mode IN ('INCREMENTAL', 'FULL'));
