-- 永洪 BI 的字段拆分依据。主台账保持一单一行；本数据集按字段输出，供筛选和透视。
CREATE OR REPLACE VIEW reporting.workshop_daily_report_field_catalog AS
WITH RECURSIVE top_level_nodes AS (
    SELECT definition_version.form_definition_id, definition_version.version_no,
           item.node, item.ordinality::INT AS root_order,
           SUM(CASE WHEN item.node ->> 'type' = 'span_layout' THEN 1 ELSE 0 END)
               OVER (PARTITION BY definition_version.form_definition_id,
                                  definition_version.version_no ORDER BY item.ordinality) AS section_no
    FROM t_form_definition_version definition_version
    JOIN t_form_definition definition ON definition.id = definition_version.form_definition_id
    CROSS JOIN LATERAL jsonb_array_elements(definition_version.schema)
        WITH ORDINALITY AS item(node, ordinality)
    WHERE definition.code = 'form_1788255825883'
), sections AS (
    SELECT form_definition_id, version_no, section_no,
           MAX(node ->> 'label') FILTER (WHERE node ->> 'type' = 'span_layout') AS section_label
    FROM top_level_nodes
    GROUP BY form_definition_id, version_no, section_no
), schema_nodes(form_definition_id, version_no, node, sort_path, section_no) AS (
    SELECT form_definition_id, version_no, node, ARRAY[root_order], section_no
    FROM top_level_nodes

    UNION ALL

    SELECT schema_nodes.form_definition_id, schema_nodes.version_no,
           child.node, schema_nodes.sort_path || child.ordinality::INT, schema_nodes.section_no
    FROM schema_nodes
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(schema_nodes.node -> 'children') = 'array'
             THEN schema_nodes.node -> 'children' ELSE '[]'::jsonb END
    ) WITH ORDINALITY AS child(node, ordinality)
), fields AS (
    SELECT DISTINCT ON (form_definition_id, version_no, node ->> 'id')
           form_definition_id, version_no, node ->> 'id' AS field_id,
           COALESCE(NULLIF(node ->> 'label', ''), node ->> 'id') AS field_label,
           node ->> 'type' AS field_type, node -> 'props' AS props,
           sort_path, section_no
    FROM schema_nodes
    WHERE COALESCE(node ->> 'id', '') <> ''
      AND node ->> 'type' <> 'span_layout'
    ORDER BY form_definition_id, version_no, node ->> 'id', sort_path
)
SELECT fields.form_definition_id, fields.version_no AS form_version, fields.field_id,
       sections.section_label, fields.field_label,
       concat_ws(' / ', NULLIF(sections.section_label, ''), fields.field_label) AS field_name,
       fields.field_type, array_to_string(fields.sort_path, '.') AS display_order,
       CASE WHEN jsonb_typeof(fields.props -> 'options') = 'array'
            THEN fields.props -> 'options' ELSE '[]'::jsonb END AS select_options,
       CASE WHEN jsonb_typeof(fields.props -> 'results') = 'array'
            THEN fields.props -> 'results' ELSE '[]'::jsonb END AS checklist_results
FROM fields
LEFT JOIN sections ON sections.form_definition_id = fields.form_definition_id
    AND sections.version_no = fields.version_no
    AND sections.section_no = fields.section_no;

CREATE OR REPLACE VIEW reporting.workshop_daily_report_field_values AS
WITH report_rows AS (
    SELECT data.id AS form_data_id, data.business_no, data.form_def_id,
           data.form_def_version AS form_version, data.created_at AS submitted_at, data.data
    FROM t_form_data data
    JOIN t_form_definition definition ON definition.id = data.form_def_id
    WHERE definition.code = 'form_1788255825883'
      AND data.status = 'SUBMITTED'
), raw_values AS (
    SELECT report_rows.form_data_id, report_rows.business_no, report_rows.form_def_id,
           report_rows.form_version,
           report_rows.submitted_at, value_item.field_id, value_item.value
    FROM report_rows
    CROSS JOIN LATERAL jsonb_each(
        CASE WHEN jsonb_typeof(report_rows.data) = 'object'
             THEN report_rows.data ELSE '{}'::jsonb END
    ) AS value_item(field_id, value)
    WHERE value_item.value <> 'null'::jsonb
      AND NOT (jsonb_typeof(value_item.value) = 'string'
               AND BTRIM(value_item.value #>> '{}') = '')
      AND NOT (jsonb_typeof(value_item.value) = 'array'
               AND jsonb_array_length(value_item.value) = 0)
)
SELECT raw_values.form_data_id, raw_values.business_no, raw_values.form_version,
       raw_values.submitted_at, raw_values.field_id,
       field.section_label, COALESCE(field.field_label, raw_values.field_id) AS field_label,
       COALESCE(field.field_name, raw_values.field_id) AS field_name,
       field.field_type, field.display_order,
       jsonb_typeof(raw_values.value) AS value_type, raw_values.value AS value_json,
       CASE
           WHEN jsonb_typeof(raw_values.value) = 'array' THEN COALESCE((
               SELECT string_agg(
                   CASE WHEN jsonb_typeof(check_item.value) = 'object' THEN
                       concat_ws('，',
                           NULLIF(check_item.value ->> 'name', ''),
                           NULLIF(COALESCE((
                               SELECT result_option.value ->> 'label'
                               FROM jsonb_array_elements(COALESCE(field.checklist_results, '[]'::jsonb))
                                   AS result_option(value)
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
               FROM jsonb_array_elements(raw_values.value)
                   WITH ORDINALITY AS check_item(value, ordinality)
           ), '')
           WHEN jsonb_typeof(raw_values.value) = 'string' THEN COALESCE((
               SELECT option.value ->> 'label'
               FROM jsonb_array_elements(COALESCE(field.select_options, '[]'::jsonb)) AS option(value)
               WHERE option.value ->> 'id' = raw_values.value #>> '{}'
                  OR option.value ->> 'value' = raw_values.value #>> '{}'
               LIMIT 1
           ), raw_values.value #>> '{}')
           ELSE raw_values.value #>> '{}'
       END AS value_text,
       CASE WHEN raw_values.value #>> '{}' ~ '^-?[0-9]+([.][0-9]+)?$'
            THEN (raw_values.value #>> '{}')::NUMERIC END AS value_number
FROM raw_values
LEFT JOIN reporting.workshop_daily_report_field_catalog field
    ON field.form_definition_id = raw_values.form_def_id
    AND field.form_version = raw_values.form_version
    AND field.field_id = raw_values.field_id;

REVOKE ALL ON TABLE reporting.workshop_daily_report_field_catalog FROM PUBLIC;
REVOKE ALL ON TABLE reporting.workshop_daily_report_field_values FROM PUBLIC;
