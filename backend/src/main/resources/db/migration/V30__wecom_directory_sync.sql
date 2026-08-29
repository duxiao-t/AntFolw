CREATE TABLE t_wecom_config (
    company_id        BIGINT PRIMARY KEY REFERENCES t_company(id) ON DELETE CASCADE,
    corp_id           VARCHAR(128) NOT NULL,
    secret_encrypted  TEXT NOT NULL,
    created_by        BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    updated_by        BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_wecom_department_mapping (
    company_id           BIGINT NOT NULL REFERENCES t_company(id) ON DELETE CASCADE,
    wecom_department_id  BIGINT NOT NULL,
    department_id        BIGINT NOT NULL REFERENCES t_department(id) ON DELETE CASCADE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, wecom_department_id),
    UNIQUE (company_id, department_id)
);

CREATE TABLE t_wecom_user_mapping (
    company_id    BIGINT NOT NULL REFERENCES t_company(id) ON DELETE CASCADE,
    wecom_user_id VARCHAR(128) NOT NULL,
    user_id       BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, wecom_user_id),
    UNIQUE (company_id, user_id)
);

CREATE TABLE t_wecom_sync_job (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT NOT NULL REFERENCES t_company(id) ON DELETE CASCADE,
    initiated_by     BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    phase            VARCHAR(32) NOT NULL DEFAULT 'CONNECTING',
    percent          INT NOT NULL DEFAULT 0,
    total_users      INT NOT NULL DEFAULT 0,
    processed_users  INT NOT NULL DEFAULT 0,
    created_users    INT NOT NULL DEFAULT 0,
    updated_users    INT NOT NULL DEFAULT 0,
    failed_users     INT NOT NULL DEFAULT 0,
    message          VARCHAR(512),
    error_summary    JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wecom_job_status CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED'
    )),
    CONSTRAINT ck_wecom_job_percent CHECK (percent BETWEEN 0 AND 100),
    CONSTRAINT ck_wecom_job_counts CHECK (
        total_users >= 0 AND processed_users >= 0 AND created_users >= 0
        AND updated_users >= 0 AND failed_users >= 0
    )
);

CREATE UNIQUE INDEX uk_wecom_company_active_job
    ON t_wecom_sync_job(company_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX ix_wecom_job_company_created
    ON t_wecom_sync_job(company_id, created_at DESC, id DESC);
