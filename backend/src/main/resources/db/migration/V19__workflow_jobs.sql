-- Persistent automation jobs for DELAY and TRIGGER process nodes.
CREATE TABLE t_workflow_job (
    id             BIGSERIAL PRIMARY KEY,
    proc_inst_id   BIGINT NOT NULL REFERENCES t_process_instance(id),
    node_id        VARCHAR(64) NOT NULL,
    job_type       VARCHAR(16) NOT NULL,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    attempts       INT NOT NULL DEFAULT 0,
    max_attempts   INT NOT NULL DEFAULT 8,
    delivery_id    UUID NOT NULL DEFAULT gen_random_uuid(),
    payload        JSONB NOT NULL DEFAULT '{}'::jsonb,
    blocking       BOOLEAN NOT NULL DEFAULT TRUE,
    last_error     TEXT,
    locked_at      TIMESTAMPTZ,
    locked_by      VARCHAR(128),
    completed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_workflow_job_type CHECK (job_type IN ('DELAY', 'TRIGGER')),
    CONSTRAINT ck_workflow_job_status CHECK (
        status IN ('SCHEDULED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_workflow_job_attempts CHECK (attempts >= 0 AND max_attempts > 0),
    CONSTRAINT uq_workflow_job_node UNIQUE (proc_inst_id, node_id, job_type),
    CONSTRAINT uq_workflow_job_delivery UNIQUE (delivery_id)
);

CREATE INDEX idx_workflow_job_due
    ON t_workflow_job(status, scheduled_at, id)
    WHERE status = 'SCHEDULED';

CREATE INDEX idx_workflow_job_instance
    ON t_workflow_job(proc_inst_id, created_at);
