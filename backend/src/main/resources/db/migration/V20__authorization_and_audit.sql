-- Action-level RBAC, per-form resource grants, instance department snapshots,
-- and append-only security auditing.

ALTER TABLE t_user
    ADD COLUMN IF NOT EXISTS authz_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE t_role
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS data_scope VARCHAR(40) NOT NULL DEFAULT 'SELF',
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS builtin BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE t_role DROP CONSTRAINT IF EXISTS ck_role_data_scope;
ALTER TABLE t_role ADD CONSTRAINT ck_role_data_scope CHECK (data_scope IN (
    'SELF', 'DEPARTMENT', 'DEPARTMENT_AND_DESCENDANTS', 'CUSTOM', 'ALL'
));

UPDATE t_role
SET builtin = true,
    enabled = true,
    data_scope = CASE code WHEN 'admin' THEN 'ALL' ELSE 'SELF' END
WHERE code IN ('admin', 'user');

INSERT INTO t_role (code, name, description, data_scope, enabled, builtin)
VALUES ('auditor', 'Security auditor', 'Read-only security audit access', 'ALL', true, true)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    data_scope = 'ALL',
    enabled = true,
    builtin = true;

CREATE TABLE IF NOT EXISTS t_permission (
    code        VARCHAR(96) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    category    VARCHAR(64) NOT NULL,
    risk_level  VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    sort_order  INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_permission_risk CHECK (risk_level IN ('NORMAL', 'HIGH', 'CRITICAL'))
);

CREATE TABLE IF NOT EXISTS t_role_permission (
    role_id          BIGINT NOT NULL REFERENCES t_role(id) ON DELETE CASCADE,
    permission_code  VARCHAR(96) NOT NULL REFERENCES t_permission(code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE IF NOT EXISTS t_role_department (
    role_id        BIGINT NOT NULL REFERENCES t_role(id) ON DELETE CASCADE,
    department_id  BIGINT NOT NULL REFERENCES t_department(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, department_id)
);

ALTER TABLE t_form_definition
    ADD COLUMN IF NOT EXISTS authz_version INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS t_form_resource_grant (
    id            BIGSERIAL PRIMARY KEY,
    form_def_id   BIGINT NOT NULL REFERENCES t_form_definition(id) ON DELETE CASCADE,
    subject_type  VARCHAR(8) NOT NULL,
    subject_id    BIGINT NOT NULL,
    granted_by    BIGINT REFERENCES t_user(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_form_grant_subject CHECK (subject_type IN ('USER', 'ROLE')),
    CONSTRAINT uk_form_grant_subject UNIQUE (form_def_id, subject_type, subject_id)
);

CREATE INDEX IF NOT EXISTS ix_form_grant_subject
    ON t_form_resource_grant(subject_type, subject_id, form_def_id);

INSERT INTO t_form_resource_grant (form_def_id, subject_type, subject_id, granted_by)
SELECT fd.id, 'USER', fd.created_by, fd.created_by
FROM t_form_definition fd
WHERE fd.created_by IS NOT NULL
ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING;

INSERT INTO t_form_resource_grant (form_def_id, subject_type, subject_id, granted_by)
SELECT fd.id, 'ROLE', admin_role.id, NULL
FROM t_form_definition fd
JOIN t_role admin_role ON admin_role.code = 'admin'
WHERE fd.created_by IS NULL
ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING;

ALTER TABLE t_process_instance
    ADD COLUMN IF NOT EXISTS started_dept_id BIGINT REFERENCES t_department(id);

UPDATE t_process_instance pi
SET started_dept_id = u.dept_id
FROM t_user u
WHERE u.id = pi.started_by
  AND pi.started_dept_id IS NULL;

CREATE INDEX IF NOT EXISTS ix_process_instance_started_dept
    ON t_process_instance(started_dept_id, started_at DESC);

INSERT INTO t_permission (code, name, category, risk_level, sort_order) VALUES
    ('security.permission.read', 'View permission catalog', 'Security', 'NORMAL', 10),
    ('security.role.read', 'View roles', 'Security', 'NORMAL', 20),
    ('security.role.write', 'Manage roles', 'Security', 'HIGH', 30),
    ('security.user_role.read', 'View user role assignments', 'Security', 'NORMAL', 40),
    ('security.user_role.write', 'Manage user role assignments', 'Security', 'HIGH', 50),
    ('security.effective.read', 'Preview effective permissions', 'Security', 'NORMAL', 60),
    ('security.audit.read', 'View audit events', 'Audit', 'HIGH', 70),
    ('security.audit.export', 'Export audit events', 'Audit', 'HIGH', 80),
    ('security.audit.archive.download', 'Download audit archives', 'Audit', 'CRITICAL', 90),
    ('org.company.manage', 'Manage company', 'Organization', 'HIGH', 100),
    ('org.department.read', 'View departments', 'Organization', 'NORMAL', 110),
    ('org.department.write', 'Manage departments', 'Organization', 'HIGH', 120),
    ('org.user.read', 'View users', 'Organization', 'NORMAL', 130),
    ('org.user.write', 'Manage users', 'Organization', 'HIGH', 140),
    ('form.definition.read', 'View form definitions', 'Forms', 'NORMAL', 200),
    ('form.definition.create', 'Create forms', 'Forms', 'HIGH', 210),
    ('form.definition.design', 'Design forms and workflows', 'Forms', 'HIGH', 220),
    ('form.definition.publish', 'Publish and disable forms', 'Forms', 'HIGH', 230),
    ('form.definition.delete', 'Delete forms', 'Forms', 'CRITICAL', 240),
    ('form.authorization.manage', 'Manage form administrators', 'Forms', 'HIGH', 250),
    ('form.runtime.read', 'Use published forms', 'Forms', 'NORMAL', 260),
    ('form.data.read', 'View form data', 'Data', 'HIGH', 270),
    ('form.data.export', 'Export form data', 'Data', 'HIGH', 280),
    ('workflow.instance.start', 'Start workflow instances', 'Workflow', 'NORMAL', 300),
    ('workflow.instance.read', 'View workflow instances', 'Workflow', 'NORMAL', 310),
    ('workflow.instance.withdraw', 'Withdraw own workflow instances', 'Workflow', 'HIGH', 320),
    ('workflow.instance.override', 'Emergency workflow intervention', 'Workflow', 'CRITICAL', 330),
    ('workflow.task.approve', 'Approve assigned tasks', 'Workflow', 'HIGH', 340),
    ('workflow.task.reject', 'Reject assigned tasks', 'Workflow', 'HIGH', 350),
    ('workflow.task.transfer', 'Transfer assigned tasks', 'Workflow', 'HIGH', 360),
    ('workflow.task.delegate', 'Delegate assigned tasks', 'Workflow', 'HIGH', 370),
    ('workflow.task.add_assignee', 'Add task assignees', 'Workflow', 'HIGH', 380),
    ('workflow.task.recall', 'Recall child tasks', 'Workflow', 'HIGH', 390),
    ('workflow.automation.retry', 'Retry workflow automation', 'Workflow', 'HIGH', 400),
    ('file.upload', 'Upload attachments', 'Files', 'NORMAL', 500),
    ('file.read', 'Read authorized attachments', 'Files', 'NORMAL', 510)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    category = EXCLUDED.category,
    risk_level = EXCLUDED.risk_level,
    sort_order = EXCLUDED.sort_order;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, permission.code
FROM t_role role CROSS JOIN t_permission permission
WHERE role.code = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, permission.code
FROM t_role role
JOIN t_permission permission ON permission.code IN (
    'form.runtime.read',
    'workflow.instance.start',
    'workflow.instance.read',
    'workflow.instance.withdraw',
    'workflow.task.approve',
    'workflow.task.reject',
    'workflow.task.transfer',
    'workflow.task.delegate',
    'workflow.task.add_assignee',
    'workflow.task.recall',
    'file.upload',
    'file.read'
)
WHERE role.code = 'user'
ON CONFLICT DO NOTHING;

INSERT INTO t_role_permission (role_id, permission_code)
SELECT role.id, permission.code
FROM t_role role
JOIN t_permission permission ON permission.code IN (
    'security.audit.read',
    'security.audit.export',
    'security.audit.archive.download'
)
WHERE role.code = 'auditor'
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS t_audit_event (
    id                  BIGSERIAL PRIMARY KEY,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    request_id          VARCHAR(64) NOT NULL,
    actor_user_id       BIGINT,
    actor_username      VARCHAR(64),
    actor_display_name  VARCHAR(128),
    session_id          UUID,
    action              VARCHAR(96) NOT NULL,
    resource_type       VARCHAR(64),
    resource_id         VARCHAR(128),
    result              VARCHAR(16) NOT NULL,
    risk_level          VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    client_ip           VARCHAR(64),
    user_agent          VARCHAR(512),
    failure_code        VARCHAR(64),
    field_diff          JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE')),
    CONSTRAINT ck_audit_risk CHECK (risk_level IN ('NORMAL', 'HIGH', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS ix_audit_event_time ON t_audit_event(occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_audit_event_actor ON t_audit_event(actor_user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_event_action ON t_audit_event(action, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_event_resource ON t_audit_event(resource_type, resource_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_event_result ON t_audit_event(result, risk_level, occurred_at DESC);

CREATE OR REPLACE FUNCTION antflow_protect_audit_event()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('antflow.audit_archive', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 't_audit_event is append-only';
    END IF;
    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_event_append_only ON t_audit_event;
CREATE TRIGGER trg_audit_event_append_only
BEFORE UPDATE OR DELETE ON t_audit_event
FOR EACH ROW EXECUTE FUNCTION antflow_protect_audit_event();

CREATE TABLE IF NOT EXISTS t_audit_archive (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    range_start   TIMESTAMPTZ NOT NULL,
    range_end     TIMESTAMPTZ NOT NULL,
    event_count   BIGINT NOT NULL,
    object_key    VARCHAR(512) NOT NULL UNIQUE,
    key_id        VARCHAR(128) NOT NULL,
    sha256        VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'READY',
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at   TIMESTAMPTZ,
    CONSTRAINT ck_audit_archive_status CHECK (status IN ('READY', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS ix_audit_archive_range
    ON t_audit_archive(range_start DESC, range_end DESC);
