-- 永洪 BI 专用：车间管理日工作清单的只读台账数据集。
-- 连接账号应仅被授予 reporting schema 的 USAGE 和本视图的 SELECT 权限。
CREATE SCHEMA IF NOT EXISTS reporting;

CREATE OR REPLACE VIEW reporting.workshop_daily_report_ledger AS
WITH RECURSIVE schema_nodes(form_definition_id, version_no, node, sort_path) AS (
    SELECT definition_version.form_definition_id, definition_version.version_no,
           item.node, ARRAY[item.ordinality::INT]
    FROM t_form_definition_version definition_version
    CROSS JOIN LATERAL jsonb_array_elements(definition_version.schema)
        WITH ORDINALITY AS item(node, ordinality)

    UNION ALL

    SELECT schema_nodes.form_definition_id, schema_nodes.version_no,
           child.node, schema_nodes.sort_path || child.ordinality::INT
    FROM schema_nodes
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(schema_nodes.node -> 'children') = 'array'
             THEN schema_nodes.node -> 'children' ELSE '[]'::jsonb END
    ) WITH ORDINALITY AS child(node, ordinality)
), field_definitions AS (
    SELECT DISTINCT ON (form_definition_id, version_no, node ->> 'id')
           form_definition_id, version_no, node ->> 'id' AS field_id,
           COALESCE(NULLIF(node ->> 'label', ''), node ->> 'id') AS label,
           node -> 'props' AS props, sort_path
    FROM schema_nodes
    WHERE COALESCE(node ->> 'id', '') <> ''
    ORDER BY form_definition_id, version_no, node ->> 'id', sort_path
), report_rows AS (
    SELECT data.id, data.business_no, data.form_def_id, data.form_def_version,
           data.data, data.created_at AS submitted_at,
           submitter.id AS submitter_id, submitter.username AS submitter_username,
           submitter.display_name AS submitter_name,
           process.id AS process_instance_id, process.status AS process_status,
           department.name AS department_name
    FROM t_form_data data
    JOIN t_form_definition definition ON definition.id = data.form_def_id
    LEFT JOIN t_user submitter ON submitter.id = data.created_by
    LEFT JOIN LATERAL (
        SELECT instance.id, instance.status, instance.started_dept_id
        FROM t_process_instance instance
        WHERE instance.form_data_id = data.id
        ORDER BY instance.started_at DESC, instance.id DESC
        LIMIT 1
    ) process ON TRUE
    LEFT JOIN t_department department ON department.id = COALESCE(
        process.started_dept_id, submitter.dept_id)
    WHERE definition.code = 'form_1788255825883'
      AND data.status = 'SUBMITTED'
), form_values AS (
    SELECT report_rows.id AS form_data_id,
           value_item.field_id,
           COALESCE(field.label, value_item.field_id) AS label,
           COALESCE(field.sort_path, ARRAY[2147483647]) AS sort_path,
           CASE
               WHEN jsonb_typeof(value_item.value) = 'array' THEN COALESCE((
                   SELECT string_agg(
                       CASE WHEN jsonb_typeof(check_item.value) = 'object' THEN
                           concat_ws('，',
                               NULLIF(check_item.value ->> 'name', ''),
                               NULLIF(COALESCE((
                                   SELECT result_option.value ->> 'label'
                                   FROM jsonb_array_elements(
                                       CASE WHEN jsonb_typeof(field.props -> 'results') = 'array'
                                            THEN field.props -> 'results' ELSE '[]'::jsonb END
                                   ) AS result_option(value)
                                   WHERE result_option.value ->> 'id' = check_item.value ->> 'status'
                                      OR result_option.value ->> 'value' = check_item.value ->> 'status'
                                   LIMIT 1
                               ), check_item.value ->> 'status'), ''),
                               NULLIF(check_item.value ->> 'description', '')
                           )
                       ELSE check_item.value #>> '{}'
                       END,
                       '；' ORDER BY check_item.ordinality
                   )
                   FROM jsonb_array_elements(value_item.value)
                       WITH ORDINALITY AS check_item(value, ordinality)
               ), '')
               WHEN jsonb_typeof(value_item.value) = 'string' THEN COALESCE((
                   SELECT option.value ->> 'label'
                   FROM jsonb_array_elements(
                       CASE WHEN jsonb_typeof(field.props -> 'options') = 'array'
                            THEN field.props -> 'options' ELSE '[]'::jsonb END
                   ) AS option(value)
                   WHERE option.value ->> 'id' = value_item.value #>> '{}'
                      OR option.value ->> 'value' = value_item.value #>> '{}'
                   LIMIT 1
               ), value_item.value #>> '{}')
               ELSE value_item.value #>> '{}'
           END AS display_value
    FROM report_rows
    CROSS JOIN LATERAL jsonb_each(
        CASE WHEN jsonb_typeof(report_rows.data) = 'object'
             THEN report_rows.data ELSE '{}'::jsonb END
    ) AS value_item(field_id, value)
    LEFT JOIN field_definitions field ON field.form_definition_id = report_rows.form_def_id
        AND field.version_no = report_rows.form_def_version
        AND field.field_id = value_item.field_id
    WHERE value_item.value <> 'null'::jsonb
      AND NOT (jsonb_typeof(value_item.value) = 'string'
               AND BTRIM(value_item.value #>> '{}') = '')
      AND NOT (jsonb_typeof(value_item.value) = 'array'
               AND jsonb_array_length(value_item.value) = 0)
), summaries AS (
    SELECT form_data_id,
           string_agg(label || '：' || display_value, E'\n' ORDER BY sort_path, field_id)
               AS form_content_summary
    FROM form_values
    WHERE display_value <> ''
    GROUP BY form_data_id
)
SELECT report_rows.id AS form_data_id,
       report_rows.business_no,
       report_rows.form_def_version AS form_version,
       report_rows.submitted_at,
       report_rows.submitter_id,
       report_rows.submitter_username,
       report_rows.submitter_name,
       report_rows.department_name,
       report_rows.process_instance_id,
       report_rows.process_status,
       COALESCE(attachments.attachment_count, 0) AS attachment_count,
       COALESCE(summaries.form_content_summary, '') AS form_content_summary,
       CASE WHEN image.file_id IS NULL THEN NULL
            ELSE '/api/public/report-images/' || report_rows.id END AS image_path
FROM report_rows
LEFT JOIN summaries ON summaries.form_data_id = report_rows.id
LEFT JOIN LATERAL (
    SELECT COUNT(*)::INT AS attachment_count
    FROM t_form_data_file link
    JOIN t_mobile_file mobile_file ON mobile_file.id = link.file_id
    WHERE link.form_data_id = report_rows.id
      AND mobile_file.deleted_at IS NULL
) attachments ON TRUE
LEFT JOIN LATERAL (
    SELECT mobile_file.id AS file_id
    FROM t_form_data_file link
    JOIN t_mobile_file mobile_file ON mobile_file.id = link.file_id
    WHERE link.form_data_id = report_rows.id
      AND mobile_file.status = 'READY'
      AND mobile_file.deleted_at IS NULL
      AND mobile_file.content_type LIKE 'image/%'
    ORDER BY link.sort_order, mobile_file.created_at, mobile_file.id
    LIMIT 1
) image ON TRUE;

REVOKE ALL ON SCHEMA reporting FROM PUBLIC;
REVOKE ALL ON TABLE reporting.workshop_daily_report_ledger FROM PUBLIC;
