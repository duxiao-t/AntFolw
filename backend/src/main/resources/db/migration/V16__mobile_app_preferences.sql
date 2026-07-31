CREATE TABLE IF NOT EXISTS t_mobile_app_preference (
    user_id BIGINT PRIMARY KEY REFERENCES t_user(id) ON DELETE CASCADE,
    form_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_mobile_app_preference_form_ids_array
        CHECK (jsonb_typeof(form_ids) = 'array')
);
