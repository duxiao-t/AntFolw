CREATE TABLE t_idempotency_record (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    http_method    VARCHAR(10) NOT NULL,
    request_path   VARCHAR(512) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash   VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    response_status INT,
    response_body  TEXT,
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_scope UNIQUE (user_id, http_method, request_path, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_idempotency_expiry ON t_idempotency_record(expires_at);

CREATE INDEX ix_task_inbox ON t_task(assignee_id, status, created_at DESC, id);
CREATE INDEX ix_task_instance_status_node ON t_task(proc_inst_id, status, node_id);
