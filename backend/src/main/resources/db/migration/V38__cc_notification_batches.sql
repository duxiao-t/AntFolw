-- One outward notification per recipient for each process round. Individual CC records remain intact.
CREATE TABLE t_cc_notification_batch (
    id           BIGSERIAL PRIMARY KEY,
    proc_inst_id BIGINT NOT NULL REFERENCES t_process_instance(id) ON DELETE CASCADE,
    round_no     INT NOT NULL,
    recipient_id BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (proc_inst_id, round_no, recipient_id),
    CONSTRAINT ck_cc_notification_batch_round CHECK (round_no > 0)
);

CREATE INDEX ix_cc_notification_batch_recipient
    ON t_cc_notification_batch(recipient_id, created_at DESC, id);
