WITH remediated_users AS (
    UPDATE t_user user_row
    SET password_hash = CASE
        -- 种子账号打成哨兵，交给 BootstrapCredentialInitializer 用配置的口令重置。
        -- 不能给它随机密码：初始化器只认哨兵行，随机密码没人知道 → 管理员直接登不进去。
        -- （进到这里的 admin/bob 一定是命中了下面 WHERE 里的共享口令，所以不必再判一次。）
        WHEN user_row.username IN ('admin', 'bob')
        THEN '!ANTFLOW_BOOTSTRAP_REQUIRED!'
        ELSE crypt(encode(gen_random_bytes(32), 'hex'), gen_salt('bf', 10))
    END
    -- 按**口令哈希**清理，不要绑"当前还有企微映射"：V36 当初给当时所有映射用户设过 qwer1234，
    -- 之后映射被删掉或改指向别人的账号会逃过整改，共享口令继续可用。
    WHERE user_row.password_hash = crypt('ant.design', user_row.password_hash)
       OR user_row.password_hash = crypt('qwer1234', user_row.password_hash)
    RETURNING user_row.id
)
UPDATE t_auth_session session
SET revoked_at = now()
WHERE session.revoked_at IS NULL
  AND session.user_id IN (SELECT id FROM remediated_users);

-- Keep BI schemas stable while enforcing the documented no-account/no-free-text boundary.
CREATE OR REPLACE VIEW v_user_catalog AS
SELECT id, display_name, NULL::VARCHAR(64) AS employee_no, dept_id FROM t_user;

CREATE OR REPLACE VIEW v_form_ledger AS
SELECT d.id                AS data_id,
       d.form_def_id,
       def.code            AS form_code,
       def.name            AS form_name,
       (def.deleted <> 0)  AS form_deleted,
       d.business_no,
       d.status            AS data_status,
       i.status            AS approval_status,
       i.started_at,
       i.finished_at,
       u.display_name      AS submitter,
       kv.key              AS field_id,
       COALESCE(c.field_label, cf.field_label) AS field_label,
       COALESCE(c.field_type, cf.field_type) AS field_type,
       COALESCE(oc.option_label, ocf.option_label) AS value_label,
       CASE WHEN COALESCE(c.field_type, cf.field_type) IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker'
       ) THEN kv.value END AS value_json,
       CASE WHEN COALESCE(c.field_type, cf.field_type) IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker'
       ) THEN kv.value #>> '{}' END AS value_text
FROM t_form_data d
JOIN t_form_definition def ON def.id = d.form_def_id
LEFT JOIN LATERAL (
    SELECT instance.status, instance.started_at, instance.finished_at
    FROM t_process_instance instance
    WHERE instance.form_data_id = d.id
    ORDER BY instance.id DESC
    LIMIT 1
) i ON true
LEFT JOIN t_user u ON u.id = d.created_by
LEFT JOIN t_form_data_revision r ON r.id = d.current_revision_id
LEFT JOIN LATERAL (
    SELECT version.id FROM t_form_definition_version version
    WHERE version.form_definition_id = def.id
    ORDER BY version.version_no DESC, version.id DESC LIMIT 1
) latest ON true
CROSS JOIN LATERAL jsonb_each(d.data) kv
LEFT JOIN v_form_field_catalog c
       ON c.version_id = r.form_definition_version_id AND c.field_id = kv.key
LEFT JOIN v_form_field_catalog cf
       ON cf.version_id = latest.id AND cf.field_id = kv.key
LEFT JOIN v_form_option_catalog oc
       ON oc.version_id = r.form_definition_version_id AND oc.field_id = kv.key
      AND oc.option_value = kv.value #>> '{}'
LEFT JOIN v_form_option_catalog ocf
       ON ocf.version_id = latest.id AND ocf.field_id = kv.key
      AND ocf.option_value = kv.value #>> '{}'
WHERE d.status = 'SUBMITTED';
