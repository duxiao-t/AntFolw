-- checklist arrays are useful to BI as raw JSON; value_label stays NULL like table_list.
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
       meta.field_label,
       meta.field_type,
       CASE WHEN meta.field_type IN ('multi_select', 'checkbox') THEN multi.labels
            ELSE oc.option_label END AS value_label,
       CASE WHEN meta.field_type IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker',
           'table_list', 'checklist'
       ) THEN kv.value END AS value_json,
       CASE WHEN meta.field_type IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker',
           'table_list', 'checklist'
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
-- ③ 该行的 form_def_version 快照。
LEFT JOIN LATERAL (
    SELECT version.id FROM t_form_definition_version version
    WHERE version.form_definition_id = def.id
      AND version.version_no = d.form_def_version
    LIMIT 1
) snapshot ON true
-- 快照行已经不存在时的最后兜底（例如版本被清理）。
LEFT JOIN LATERAL (
    SELECT version.id FROM t_form_definition_version version
    WHERE version.form_definition_id = def.id
    ORDER BY version.version_no DESC, version.id DESC LIMIT 1
) latest ON true
CROSS JOIN LATERAL jsonb_each(d.data) kv
-- ② 一次定死版本，后面所有字典 join 共用它，不再逐列 COALESCE。
LEFT JOIN LATERAL (
    SELECT field.field_label, field.field_type
    FROM v_form_field_catalog field
    WHERE field.version_id = COALESCE(r.form_definition_version_id, snapshot.id, latest.id)
      AND field.field_id = kv.key
    LIMIT 1
) meta ON true
-- ④ multi_select：逐元素查字典，命不中的原样保留（比丢掉更能看出脏数据）。
LEFT JOIN LATERAL (
    SELECT string_agg(COALESCE(dict.option_label, element.elem), '、' ORDER BY element.ord)
               AS labels
    FROM jsonb_array_elements_text(
        CASE WHEN jsonb_typeof(kv.value) = 'array' THEN kv.value ELSE '[]'::jsonb END)
        WITH ORDINALITY AS element(elem, ord)
    LEFT JOIN v_form_option_catalog dict
           ON dict.version_id = COALESCE(r.form_definition_version_id, snapshot.id, latest.id)
          AND dict.field_id = kv.key
          AND dict.option_value = element.elem
    WHERE meta.field_type IN ('multi_select', 'checkbox')
) multi ON true
LEFT JOIN v_form_option_catalog oc
       ON oc.version_id = COALESCE(r.form_definition_version_id, snapshot.id, latest.id)
      AND oc.field_id = kv.key
      AND oc.option_value = kv.value #>> '{}'
WHERE d.status = 'SUBMITTED';
