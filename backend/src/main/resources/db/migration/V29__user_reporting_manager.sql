ALTER TABLE t_user
    ADD COLUMN manager_id BIGINT;

ALTER TABLE t_user
    ADD CONSTRAINT fk_user_manager
        FOREIGN KEY (manager_id) REFERENCES t_user(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_user_manager_not_self
        CHECK (manager_id IS NULL OR manager_id <> id);

CREATE INDEX ix_user_manager ON t_user(manager_id);
