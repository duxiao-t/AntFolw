ALTER TABLE t_form_data ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE t_mobile_file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id BIGINT NOT NULL REFERENCES t_user(id),
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX ix_mobile_file_owner ON t_mobile_file(owner_id, created_at DESC);

CREATE TABLE t_form_data_file (
    form_data_id BIGINT NOT NULL REFERENCES t_form_data(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES t_mobile_file(id),
    field_id VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (form_data_id, file_id)
);
CREATE INDEX ix_form_data_file_file ON t_form_data_file(file_id);
