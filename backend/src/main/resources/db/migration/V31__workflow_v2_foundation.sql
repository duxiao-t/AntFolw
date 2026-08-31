-- Workflow V2 foundations. Existing instances remain engine_version=1 and keep
-- using their frozen JSON snapshots; new instances opt into these structures.

CREATE TABLE t_form_definition_version (
    id                   BIGSERIAL PRIMARY KEY,
    form_definition_id   BIGINT NOT NULL REFERENCES t_form_definition(id),
    version_no           INT NOT NULL,
    schema               JSONB NOT NULL,
    settings             JSONB NOT NULL DEFAULT '{}'::jsonb,
    checksum             VARCHAR(64) NOT NULL,
    legacy_approximate   BOOLEAN NOT NULL DEFAULT FALSE,
    published_by         BIGINT REFERENCES t_user(id),
    published_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (form_definition_id, version_no)
);

CREATE TABLE t_process_definition_version (
    id                         BIGSERIAL PRIMARY KEY,
    process_definition_id      BIGINT NOT NULL REFERENCES t_process_definition(id),
    form_definition_version_id BIGINT NOT NULL REFERENCES t_form_definition_version(id),
    version_no                 INT NOT NULL,
    process                    JSONB NOT NULL,
    settings                   JSONB NOT NULL DEFAULT '{}'::jsonb,
    checksum                   VARCHAR(64) NOT NULL,
    legacy_approximate         BOOLEAN NOT NULL DEFAULT FALSE,
    published_by               BIGINT REFERENCES t_user(id),
    published_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_definition_id, version_no, checksum)
);

-- Existing databases have only the latest form schema. Older version rows are
-- retained as explicitly approximate rather than pretending the old schema is known.
WITH versions AS (
    SELECT id AS form_definition_id, version AS version_no FROM t_form_definition
    UNION
    SELECT form_def_id, form_def_version FROM t_form_data
)
INSERT INTO t_form_definition_version(
    form_definition_id, version_no, schema, settings, checksum,
    legacy_approximate, published_by, published_at)
SELECT form.id, versions.version_no, form.schema, form.settings,
       encode(digest((form.schema::text || form.settings::text)::bytea, 'sha256'), 'hex'),
       versions.version_no <> form.version, form.created_by,
       COALESCE(form.updated_at, form.created_at, now())
FROM versions
JOIN t_form_definition form ON form.id = versions.form_definition_id
ON CONFLICT (form_definition_id, version_no) DO NOTHING;

WITH snapshots AS (
    SELECT pi.proc_def_id AS process_definition_id,
           COALESCE(pi.process_def_version, process.version) AS version_no,
           COALESCE(pi.process_snapshot, process.process) AS process_json,
           data.form_def_version,
           TRUE AS legacy_approximate,
           process.created_by,
           pi.started_at AS published_at
    FROM t_process_instance pi
    JOIN t_process_definition process ON process.id = pi.proc_def_id
    JOIN t_form_data data ON data.id = pi.form_data_id
    UNION ALL
    SELECT process.id, process.version, process.process, form.version,
           FALSE, process.created_by, process.created_at
    FROM t_process_definition process
    JOIN t_form_definition form ON form.id = process.form_def_id
    WHERE process.status = 'PUBLISHED'
), normalized AS (
    SELECT DISTINCT ON (process_definition_id, version_no,
                        encode(digest(process_json::text::bytea, 'sha256'), 'hex'))
           snapshots.*,
           encode(digest(process_json::text::bytea, 'sha256'), 'hex') AS checksum
    FROM snapshots
    WHERE process_json IS NOT NULL
)
INSERT INTO t_process_definition_version(
    process_definition_id, form_definition_version_id, version_no, process,
    checksum, legacy_approximate, published_by, published_at)
SELECT normalized.process_definition_id, form_version.id, normalized.version_no,
       normalized.process_json, normalized.checksum, normalized.legacy_approximate,
       normalized.created_by, COALESCE(normalized.published_at, now())
FROM normalized
JOIN t_process_definition process ON process.id = normalized.process_definition_id
JOIN t_form_definition_version form_version
  ON form_version.form_definition_id = process.form_def_id
 AND form_version.version_no = normalized.form_def_version
ON CONFLICT (process_definition_id, version_no, checksum) DO NOTHING;

CREATE TABLE t_form_data_revision (
    id                   BIGSERIAL PRIMARY KEY,
    form_data_id         BIGINT NOT NULL REFERENCES t_form_data(id),
    revision_no          INT NOT NULL,
    form_definition_version_id BIGINT NOT NULL REFERENCES t_form_definition_version(id),
    data                 JSONB NOT NULL,
    status               VARCHAR(24) NOT NULL,
    reason               VARCHAR(32) NOT NULL,
    checksum             VARCHAR(64) NOT NULL,
    created_by           BIGINT REFERENCES t_user(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (form_data_id, revision_no),
    CONSTRAINT ck_form_revision_status CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'NEEDS_REVISION')
    )
);

CREATE TABLE t_form_data_revision_file (
    revision_id BIGINT NOT NULL REFERENCES t_form_data_revision(id) ON DELETE CASCADE,
    file_id     UUID NOT NULL REFERENCES t_mobile_file(id),
    field_id    VARCHAR(128) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    PRIMARY KEY (revision_id, file_id, field_id)
);

INSERT INTO t_form_data_revision(
    form_data_id, revision_no, form_definition_version_id, data, status,
    reason, checksum, created_by, created_at)
SELECT data.id, 1, version.id, data.data,
       CASE WHEN data.status = 'DRAFT' THEN 'DRAFT'
            WHEN data.status = 'NEEDS_REVISION' THEN 'NEEDS_REVISION'
            ELSE 'SUBMITTED' END,
       'LEGACY_IMPORT', encode(digest(data.data::text::bytea, 'sha256'), 'hex'),
       data.created_by, data.created_at
FROM t_form_data data
JOIN t_form_definition_version version
  ON version.form_definition_id = data.form_def_id
 AND version.version_no = data.form_def_version
ON CONFLICT (form_data_id, revision_no) DO NOTHING;

ALTER TABLE t_form_data ADD COLUMN current_revision_id BIGINT;
UPDATE t_form_data data
SET current_revision_id = revision.id
FROM t_form_data_revision revision
WHERE revision.form_data_id = data.id AND revision.revision_no = 1;
ALTER TABLE t_form_data ADD CONSTRAINT fk_form_data_current_revision
    FOREIGN KEY (current_revision_id) REFERENCES t_form_data_revision(id);

ALTER TABLE t_process_instance
    ADD COLUMN engine_version INT NOT NULL DEFAULT 1,
    ADD COLUMN process_definition_version_id BIGINT,
    ADD COLUMN round_no INT NOT NULL DEFAULT 1,
    ADD COLUMN current_node_instance_id BIGINT,
    ADD COLUMN current_form_revision_id BIGINT;

UPDATE t_process_instance instance
SET process_definition_version_id = version.id
FROM t_process_definition_version version
WHERE version.process_definition_id = instance.proc_def_id
  AND version.version_no = instance.process_def_version
  AND version.checksum = encode(digest(instance.process_snapshot::text::bytea, 'sha256'), 'hex');

UPDATE t_process_instance instance
SET current_form_revision_id = data.current_revision_id
FROM t_form_data data
WHERE data.id = instance.form_data_id;

ALTER TABLE t_process_instance
    ADD CONSTRAINT fk_instance_process_version
        FOREIGN KEY (process_definition_version_id) REFERENCES t_process_definition_version(id),
    ADD CONSTRAINT fk_instance_form_revision
        FOREIGN KEY (current_form_revision_id) REFERENCES t_form_data_revision(id),
    ADD CONSTRAINT ck_instance_engine_version CHECK (engine_version IN (1, 2)),
    ADD CONSTRAINT ck_instance_round CHECK (round_no > 0);

CREATE TABLE t_process_node_instance (
    id                       BIGSERIAL PRIMARY KEY,
    proc_inst_id             BIGINT NOT NULL REFERENCES t_process_instance(id),
    node_id                  VARCHAR(64) NOT NULL,
    node_type                VARCHAR(32) NOT NULL,
    round_no                 INT NOT NULL,
    attempt_no               INT NOT NULL,
    parent_node_instance_id  BIGINT REFERENCES t_process_node_instance(id),
    gateway_node_instance_id BIGINT REFERENCES t_process_node_instance(id),
    branch_id                VARCHAR(64),
    status                   VARCHAR(24) NOT NULL,
    policy_snapshot          JSONB NOT NULL DEFAULT '{}'::jsonb,
    form_revision_id_at_enter BIGINT REFERENCES t_form_data_revision(id),
    version                  INT NOT NULL DEFAULT 0,
    started_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at             TIMESTAMPTZ,
    CONSTRAINT ck_node_instance_status CHECK (
        status IN ('ACTIVE', 'PASSED', 'REJECTED', 'CANCELLED', 'AUTO_PASSED')
    ),
    CONSTRAINT ck_node_instance_round CHECK (round_no > 0 AND attempt_no > 0),
    UNIQUE (proc_inst_id, node_id, round_no, attempt_no)
);

ALTER TABLE t_process_instance ADD CONSTRAINT fk_instance_current_node
    FOREIGN KEY (current_node_instance_id) REFERENCES t_process_node_instance(id);

CREATE TABLE t_parallel_branch_state (
    gateway_node_instance_id BIGINT NOT NULL REFERENCES t_process_node_instance(id) ON DELETE CASCADE,
    branch_id                VARCHAR(64) NOT NULL,
    status                   VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    completed_at             TIMESTAMPTZ,
    PRIMARY KEY (gateway_node_instance_id, branch_id),
    CONSTRAINT ck_parallel_branch_status CHECK (
        status IN ('ACTIVE', 'PASSED', 'REJECTED', 'CANCELLED')
    )
);

CREATE TABLE t_node_participant (
    id               BIGSERIAL PRIMARY KEY,
    node_instance_id BIGINT NOT NULL REFERENCES t_process_node_instance(id) ON DELETE CASCADE,
    responsible_user_id BIGINT NOT NULL REFERENCES t_user(id),
    actual_user_id      BIGINT NOT NULL REFERENCES t_user(id),
    sequence_no         INT NOT NULL DEFAULT 1,
    status              VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    source              VARCHAR(32) NOT NULL,
    UNIQUE (node_instance_id, responsible_user_id, sequence_no),
    CONSTRAINT ck_node_participant_status CHECK (
        status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
    )
);

ALTER TABLE t_task
    ADD COLUMN node_instance_id BIGINT REFERENCES t_process_node_instance(id),
    ADD COLUMN action_form_revision_id BIGINT REFERENCES t_form_data_revision(id),
    ADD COLUMN sequence_no INT,
    ADD COLUMN operation_kind VARCHAR(24),
    ADD COLUMN timeout_at TIMESTAMPTZ;

CREATE INDEX ix_node_instance_active
    ON t_process_node_instance(proc_inst_id, status, round_no, id);
CREATE INDEX ix_node_instance_gateway
    ON t_process_node_instance(gateway_node_instance_id, status, id);
CREATE INDEX ix_task_node_status ON t_task(node_instance_id, status, id);
CREATE UNIQUE INDEX ux_task_pending_participant
    ON t_task(node_instance_id, assignee_id, sequence_no)
    WHERE status = 'PENDING' AND node_instance_id IS NOT NULL;

CREATE TABLE t_cc_record (
    id               BIGSERIAL PRIMARY KEY,
    proc_inst_id     BIGINT NOT NULL REFERENCES t_process_instance(id),
    node_instance_id BIGINT REFERENCES t_process_node_instance(id),
    recipient_id     BIGINT NOT NULL REFERENCES t_user(id),
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (node_instance_id, recipient_id)
);
CREATE INDEX ix_cc_recipient_read ON t_cc_record(recipient_id, read_at, created_at DESC, id);

CREATE TABLE t_workflow_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(32) NOT NULL,
    aggregate_id    BIGINT NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    recipient_id    BIGINT REFERENCES t_user(id),
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,
    locked_by       VARCHAR(128),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'RUNNING', 'DELIVERED', 'DEAD'))
);
CREATE INDEX ix_outbox_due ON t_workflow_outbox(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE t_user_notification (
    id          BIGSERIAL PRIMARY KEY,
    event_id    UUID NOT NULL REFERENCES t_workflow_outbox(id),
    user_id     BIGINT NOT NULL REFERENCES t_user(id),
    event_type  VARCHAR(64) NOT NULL,
    title       VARCHAR(256) NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, user_id)
);
CREATE INDEX ix_user_notification_inbox
    ON t_user_notification(user_id, read_at, created_at DESC, id);

CREATE TABLE t_approval_delegation (
    id            BIGSERIAL PRIMARY KEY,
    principal_id  BIGINT NOT NULL REFERENCES t_user(id),
    agent_id      BIGINT NOT NULL REFERENCES t_user(id),
    form_def_id   BIGINT REFERENCES t_form_definition(id),
    starts_at     TIMESTAMPTZ NOT NULL,
    ends_at       TIMESTAMPTZ NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by    BIGINT NOT NULL REFERENCES t_user(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_delegation_users CHECK (principal_id <> agent_id),
    CONSTRAINT ck_delegation_window CHECK (starts_at < ends_at),
    CONSTRAINT ck_delegation_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX ix_delegation_active
    ON t_approval_delegation(principal_id, starts_at, ends_at)
    WHERE status = 'ACTIVE';

ALTER TABLE t_workflow_job DROP CONSTRAINT ck_workflow_job_type;
ALTER TABLE t_workflow_job DROP CONSTRAINT uq_workflow_job_node;
ALTER TABLE t_workflow_job ALTER COLUMN job_type TYPE VARCHAR(32);
ALTER TABLE t_workflow_job
    ADD COLUMN task_id BIGINT REFERENCES t_task(id),
    ADD COLUMN node_instance_id BIGINT REFERENCES t_process_node_instance(id),
    ADD COLUMN action_key VARCHAR(64),
    ADD CONSTRAINT ck_workflow_job_type CHECK (
        job_type IN ('DELAY', 'TRIGGER', 'TASK_TIMEOUT')
    );
CREATE UNIQUE INDEX ux_workflow_node_job
    ON t_workflow_job(proc_inst_id, node_id, job_type)
    WHERE job_type IN ('DELAY', 'TRIGGER');
CREATE UNIQUE INDEX ux_workflow_task_timeout
    ON t_workflow_job(task_id, action_key)
    WHERE job_type = 'TASK_TIMEOUT';

CREATE OR REPLACE FUNCTION antflow_protect_task_history()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 't_task_history is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_task_history_append_only ON t_task_history;
CREATE TRIGGER trg_task_history_append_only
BEFORE UPDATE OR DELETE ON t_task_history
FOR EACH ROW EXECUTE FUNCTION antflow_protect_task_history();
