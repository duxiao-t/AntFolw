CREATE TABLE IF NOT EXISTS t_auth_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    csrf_token_hash VARCHAR(64) NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    platform VARCHAR(16) NOT NULL DEFAULT 'browser',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_auth_session_user_active
    ON t_auth_session(user_id, last_active_at DESC)
    WHERE revoked_at IS NULL;
