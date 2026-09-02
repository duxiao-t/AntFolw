-- Form visibility can target a department subtree without expanding thousands of users.
ALTER TABLE t_form_resource_grant DROP CONSTRAINT IF EXISTS ck_form_grant_subject;
ALTER TABLE t_form_resource_grant ALTER COLUMN subject_type TYPE VARCHAR(16);
ALTER TABLE t_form_resource_grant ADD CONSTRAINT ck_form_grant_subject
    CHECK (subject_type IN ('USER', 'ROLE', 'DEPARTMENT'));

-- Enterprise WeCom UserID is the authoritative employee number. Abort before
-- changing anything if an unrelated local account already owns one.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM t_wecom_user_mapping mapping
        JOIN t_user owner ON owner.employee_no = mapping.wecom_user_id
        LEFT JOIN t_wecom_user_mapping owner_mapping
          ON owner_mapping.user_id = owner.id
         AND owner_mapping.company_id = mapping.company_id
        WHERE owner.id <> mapping.user_id AND owner_mapping.user_id IS NULL
    ) THEN
        RAISE EXCEPTION 'an unmapped local user owns a WeCom UserID employee number';
    END IF;
    IF EXISTS (
        SELECT wecom_user_id FROM t_wecom_user_mapping
        GROUP BY wecom_user_id HAVING count(DISTINCT user_id) > 1
    ) THEN
        RAISE EXCEPTION 'the same WeCom UserID maps to multiple local users';
    END IF;
    IF EXISTS (
        SELECT 1 FROM t_wecom_user_mapping
        WHERE wecom_user_id IS NULL OR btrim(wecom_user_id) = ''
           OR length(wecom_user_id) > 64 OR wecom_user_id ~ '[[:space:][:cntrl:]]'
    ) THEN
        RAISE EXCEPTION 'a WeCom UserID cannot be used as an employee number';
    END IF;
    IF EXISTS (
        SELECT 1 FROM t_user user_row
        WHERE user_row.employee_no LIKE '__wc_tmp_%'
          AND NOT EXISTS (SELECT 1 FROM t_wecom_user_mapping mapping
                          WHERE mapping.user_id = user_row.id)
    ) THEN
        RAISE EXCEPTION 'a local employee number uses the reserved WeCom migration prefix';
    END IF;
END $$;

ALTER TABLE t_user DROP CONSTRAINT IF EXISTS ck_user_employee_no;
ALTER TABLE t_user ALTER COLUMN employee_no TYPE VARCHAR(64);

UPDATE t_user user_row
SET employee_no = '__wc_tmp_' || user_row.id
WHERE EXISTS (SELECT 1 FROM t_wecom_user_mapping mapping WHERE mapping.user_id = user_row.id);

UPDATE t_user user_row
SET employee_no = mapping.wecom_user_id
FROM t_wecom_user_mapping mapping
WHERE mapping.user_id = user_row.id;

ALTER TABLE t_user ADD CONSTRAINT ck_user_employee_no
    CHECK (length(employee_no) BETWEEN 1 AND 64
       AND employee_no !~ '[[:space:][:cntrl:]]');

-- Existing legacy numbers remain valid. Custom form numbers may be longer.
ALTER TABLE t_form_data DROP CONSTRAINT IF EXISTS ck_form_data_business_no;
ALTER TABLE t_form_data ALTER COLUMN business_no TYPE VARCHAR(128);
ALTER TABLE t_form_data ADD CONSTRAINT ck_form_data_business_no
    CHECK (business_no IS NULL OR (length(business_no) BETWEEN 1 AND 128
        AND business_no !~ '[[:cntrl:]]'));

CREATE UNIQUE INDEX ux_form_number_namespace
    ON t_form_definition (lower(settings #>> '{businessNumber,namespace}'))
    WHERE status = 'PUBLISHED'
      AND settings @> '{"businessNumber":{"enabled":true}}'::jsonb;

CREATE TABLE t_form_number_counter (
    form_def_id BIGINT NOT NULL REFERENCES t_form_definition(id) ON DELETE CASCADE,
    period_key  VARCHAR(16) NOT NULL,
    value       BIGINT NOT NULL,
    PRIMARY KEY (form_def_id, period_key),
    CONSTRAINT ck_form_number_counter_value CHECK (value > 0)
);

CREATE TABLE t_system_backup_setting (
    id             SMALLINT PRIMARY KEY DEFAULT 1,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    local_time     TIME NOT NULL DEFAULT '02:30',
    retention_days INT NOT NULL DEFAULT 30,
    version        INT NOT NULL DEFAULT 0,
    updated_by     BIGINT REFERENCES t_user(id),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_backup_singleton CHECK (id = 1),
    CONSTRAINT ck_backup_retention CHECK (retention_days BETWEEN 1 AND 365)
);
INSERT INTO t_system_backup_setting(id) VALUES (1) ON CONFLICT DO NOTHING;

DELETE FROM t_role_permission WHERE permission_code = 'page.settings.billing';
DELETE FROM t_permission WHERE code = 'page.settings.billing';

INSERT INTO t_permission(code, name, category, risk_level, sort_order, kind, admin_only)
VALUES
    ('page.settings.backup', '系统备份', '系统设置', 'CRITICAL', 53, 'PAGE', true),
    ('system.backup.manage', '管理系统备份', '系统设置', 'CRITICAL', 540, 'ACTION', true)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name, category = EXCLUDED.category,
    risk_level = EXCLUDED.risk_level, sort_order = EXCLUDED.sort_order,
    kind = EXCLUDED.kind, admin_only = EXCLUDED.admin_only;

INSERT INTO t_role_permission(role_id, permission_code)
SELECT role.id, permission.code
FROM t_role role
JOIN t_permission permission ON permission.code IN ('page.settings.backup', 'system.backup.manage')
WHERE role.code = 'admin'
ON CONFLICT DO NOTHING;
