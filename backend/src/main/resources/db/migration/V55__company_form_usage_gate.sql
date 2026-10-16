ALTER TABLE t_form_resource_grant DROP CONSTRAINT ck_form_grant_subject;
ALTER TABLE t_form_resource_grant ADD CONSTRAINT ck_form_grant_subject
    CHECK (subject_type IN ('USER', 'ROLE', 'DEPARTMENT', 'COMPANY'));

-- Preserve existing subject grants; companies created after this migration start without access.
INSERT INTO t_form_resource_grant (form_def_id, subject_type, subject_id)
SELECT form.id, 'COMPANY', company.id
FROM t_form_definition form CROSS JOIN t_company company
WHERE form.deleted = 0
ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING;
