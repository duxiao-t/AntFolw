-- 可复用下拉数据源：导入生成不可变版本，表单发布快照只引用确定版本。
CREATE TABLE t_option_data_source (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version     INT NOT NULL DEFAULT 0,
    created_by  BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_option_source_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE t_option_data_source_version (
    id             BIGSERIAL PRIMARY KEY,
    source_id      BIGINT NOT NULL REFERENCES t_option_data_source(id) ON DELETE RESTRICT,
    version_no     INT NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    columns_json   JSONB NOT NULL,
    row_count      INT NOT NULL,
    original_name  VARCHAR(255),
    sha256         VARCHAR(64) NOT NULL,
    created_by     BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    published_by   BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT uk_option_source_version UNIQUE (source_id, version_no),
    CONSTRAINT ck_option_source_version_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_option_source_row_count CHECK (row_count >= 0)
);

CREATE UNIQUE INDEX uk_option_source_single_draft
    ON t_option_data_source_version(source_id) WHERE status = 'DRAFT';

CREATE TABLE t_option_data_source_row (
    version_id  BIGINT NOT NULL REFERENCES t_option_data_source_version(id) ON DELETE CASCADE,
    row_no      INT NOT NULL,
    data        JSONB NOT NULL,
    PRIMARY KEY (version_id, row_no)
);

CREATE INDEX ix_option_source_row_data
    ON t_option_data_source_row USING GIN (data jsonb_path_ops);

CREATE TABLE t_option_data_source_grant (
    source_id    BIGINT NOT NULL REFERENCES t_option_data_source(id) ON DELETE CASCADE,
    subject_type VARCHAR(16) NOT NULL,
    subject_id   BIGINT NOT NULL,
    granted_by   BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_id, subject_type, subject_id),
    CONSTRAINT ck_option_source_grant_subject CHECK (subject_type IN ('USER', 'ROLE'))
);

CREATE INDEX ix_option_source_grant_subject
    ON t_option_data_source_grant(subject_type, subject_id, source_id);
