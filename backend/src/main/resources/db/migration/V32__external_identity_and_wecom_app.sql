-- External identity providers and enterprise WeCom application capabilities.

CREATE TABLE t_oidc_provider (
    id                      BIGSERIAL PRIMARY KEY,
    code                    VARCHAR(64) NOT NULL UNIQUE,
    display_name            VARCHAR(128) NOT NULL,
    issuer_uri              VARCHAR(512) NOT NULL,
    client_id               VARCHAR(256) NOT NULL,
    client_secret_encrypted TEXT NOT NULL,
    client_auth_method      VARCHAR(16) NOT NULL DEFAULT 'BASIC',
    scopes                  VARCHAR(512) NOT NULL DEFAULT 'openid profile email',
    match_claim             VARCHAR(128) NOT NULL DEFAULT 'preferred_username',
    match_field             VARCHAR(32) NOT NULL DEFAULT 'username',
    enabled                 BOOLEAN NOT NULL DEFAULT true,
    created_by              BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    updated_by              BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_oidc_client_auth CHECK (client_auth_method IN ('BASIC', 'POST')),
    CONSTRAINT ck_oidc_match_field CHECK (match_field IN ('username', 'email', 'employeeNo'))
);

CREATE TABLE t_oidc_identity_binding (
    id            BIGSERIAL PRIMARY KEY,
    provider_id   BIGINT NOT NULL REFERENCES t_oidc_provider(id) ON DELETE CASCADE,
    subject       VARCHAR(512) NOT NULL,
    user_id       BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_id, subject),
    UNIQUE (provider_id, user_id)
);

CREATE TABLE t_external_auth_flow (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    state_hash          VARCHAR(64) NOT NULL UNIQUE,
    provider_type       VARCHAR(16) NOT NULL,
    provider_id         BIGINT NOT NULL,
    nonce               VARCHAR(128),
    pkce_verifier_encrypted TEXT,
    return_path         VARCHAR(1024) NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_external_auth_provider CHECK (provider_type IN ('OIDC', 'WECOM'))
);
CREATE INDEX ix_external_auth_flow_expiry ON t_external_auth_flow(expires_at)
    WHERE consumed_at IS NULL;

ALTER TABLE t_wecom_config
    ADD COLUMN agent_id INT,
    ADD COLUMN agent_secret_encrypted TEXT,
    ADD COLUMN oauth_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN js_sdk_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN message_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX ux_wecom_single_oauth_corp ON t_wecom_config(oauth_enabled)
    WHERE oauth_enabled;

CREATE TABLE t_wecom_message_delivery (
    id              BIGSERIAL PRIMARY KEY,
    dedupe_key      VARCHAR(256) NOT NULL UNIQUE,
    event_type      VARCHAR(64) NOT NULL,
    proc_inst_id    BIGINT REFERENCES t_process_instance(id) ON DELETE CASCADE,
    task_id         BIGINT REFERENCES t_task(id) ON DELETE SET NULL,
    recipient_id    BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    title           VARCHAR(256) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,
    locked_by       VARCHAR(128),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT ck_wecom_delivery_status CHECK (status IN ('PENDING', 'RUNNING', 'DELIVERED', 'DEAD'))
);
CREATE INDEX ix_wecom_delivery_due
    ON t_wecom_message_delivery(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RUNNING');

INSERT INTO t_permission(code, name, category, risk_level, sort_order, kind, admin_only)
VALUES ('page.settings.identity_providers', '身份提供方', '系统设置', 'HIGH', 53, 'PAGE', true)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name, category = EXCLUDED.category, risk_level = EXCLUDED.risk_level,
    sort_order = EXCLUDED.sort_order, kind = EXCLUDED.kind, admin_only = EXCLUDED.admin_only;

INSERT INTO t_role_permission(role_id, permission_code)
SELECT id, 'page.settings.identity_providers' FROM t_role WHERE code = 'admin'
ON CONFLICT DO NOTHING;
