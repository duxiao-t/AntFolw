-- V1__init.sql
-- AntFlow schema baseline.
--
-- Extensions: previously loaded by initdb.d/01-extensions.sql on docker-compose
-- startup. For local (non-docker) deployments where the user runs against an
-- existing PG instance, Flyway executes this file as the migration owner.
-- CREATE EXTENSION IF NOT EXISTS is a no-op when the extension already exists,
-- so it's safe to include here. Requires superuser or extension-related grants
-- on the connecting role.

CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Organization
CREATE TABLE t_company (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_department (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT NOT NULL REFERENCES t_company(id),
    parent_id    BIGINT REFERENCES t_department(id),
    path         LTREE NOT NULL,
    name         VARCHAR(128) NOT NULL,
    leader_id    BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_role (
    id    BIGSERIAL PRIMARY KEY,
    code  VARCHAR(64) NOT NULL UNIQUE,
    name  VARCHAR(128) NOT NULL
);

CREATE TABLE t_user (
    id              BIGSERIAL PRIMARY KEY,
    dept_id         BIGINT REFERENCES t_department(id),
    username        VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    email           VARCHAR(255),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE t_department ADD CONSTRAINT fk_dept_leader FOREIGN KEY (leader_id) REFERENCES t_user(id);

CREATE TABLE t_user_role (
    user_id BIGINT NOT NULL REFERENCES t_user(id),
    role_id BIGINT NOT NULL REFERENCES t_role(id),
    PRIMARY KEY (user_id, role_id)
);

-- Form designer + runtime
CREATE TABLE t_form_definition (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,           -- DB-level UNIQUE
    name          VARCHAR(128) NOT NULL,
    version       INT NOT NULL DEFAULT 1,
    schema        JSONB NOT NULL,
    settings      JSONB NOT NULL DEFAULT '{}'::jsonb,
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PUBLISHED/DEPRECATED
    created_by    BIGINT REFERENCES t_user(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_form_data (
    id                BIGSERIAL PRIMARY KEY,
    form_def_id       BIGINT NOT NULL REFERENCES t_form_definition(id),
    form_def_version  INT NOT NULL,
    data              JSONB NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED', -- DRAFT or SUBMITTED
    created_by        BIGINT REFERENCES t_user(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Process designer + engine state
CREATE TABLE t_process_definition (
    id          BIGSERIAL PRIMARY KEY,
    form_def_id BIGINT NOT NULL UNIQUE REFERENCES t_form_definition(id),  -- 1:1 with form in MVP
    version     INT NOT NULL DEFAULT 1,
    nodes       JSONB NOT NULL,
    edges       JSONB NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by  BIGINT REFERENCES t_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_process_instance (
    id               BIGSERIAL PRIMARY KEY,
    proc_def_id      BIGINT NOT NULL REFERENCES t_process_definition(id),
    form_data_id     BIGINT NOT NULL REFERENCES t_form_data(id),
    status           VARCHAR(16) NOT NULL DEFAULT 'RUNNING', -- RUNNING/APPROVED/REJECTED/WITHDRAWN
    current_node_id  VARCHAR(64),
    version          INT NOT NULL DEFAULT 0,                 -- @Version optimistic lock
    started_by       BIGINT REFERENCES t_user(id),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);

CREATE TABLE t_task (
    id              BIGSERIAL PRIMARY KEY,
    proc_inst_id    BIGINT NOT NULL REFERENCES t_process_instance(id),
    node_id         VARCHAR(64) NOT NULL,
    assignee_id     BIGINT NOT NULL REFERENCES t_user(id),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED/SKIPPED
    approval_mode   VARCHAR(16) NOT NULL DEFAULT 'OR_SIGN',  -- reserved for future ALL_SIGN
    version         INT NOT NULL DEFAULT 0,                 -- @Version optimistic lock
    approved_by     BIGINT REFERENCES t_user(id),
    approved_at     TIMESTAMPTZ,
    comment         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_task_history (
    id              BIGSERIAL PRIMARY KEY,
    proc_inst_id    BIGINT NOT NULL REFERENCES t_process_instance(id),
    from_node_id    VARCHAR(64),
    to_node_id      VARCHAR(64),
    task_id         BIGINT REFERENCES t_task(id),
    action          VARCHAR(32) NOT NULL, -- START/APPROVE/REJECT/SKIP/WITHDRAW/COMPLETE
    operator_id     BIGINT REFERENCES t_user(id),
    comment         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
