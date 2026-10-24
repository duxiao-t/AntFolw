-- One row per checklist item, resolved with the same version as the ledger row.
CREATE OR REPLACE VIEW v_form_ledger_checklist AS
WITH rows AS (
    SELECT d.id AS data_id,
           d.form_def_id,
           def.code AS form_code,
           def.name AS form_name,
           (def.deleted <> 0) AS form_deleted,
           d.business_no,
           d.status AS data_status,
           i.status AS approval_status,
           i.started_at,
           i.finished_at,
           u.display_name AS submitter,
           d.form_def_version,
           kv.key AS field_id,
           meta.field_label,
           kv.value AS raw_value,
           version.schema AS form_schema
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
        SELECT snapshot.id FROM t_form_definition_version snapshot
        WHERE snapshot.form_definition_id = def.id
          AND snapshot.version_no = d.form_def_version
        LIMIT 1
    ) snapshot ON true
    LEFT JOIN LATERAL (
        SELECT latest.id FROM t_form_definition_version latest
        WHERE latest.form_definition_id = def.id
        ORDER BY latest.version_no DESC, latest.id DESC
        LIMIT 1
    ) latest ON true
    CROSS JOIN LATERAL jsonb_each(d.data) kv
    LEFT JOIN LATERAL (
        SELECT field.field_label, field.field_type
        FROM v_form_field_catalog field
        WHERE field.version_id = COALESCE(r.form_definition_version_id, snapshot.id, latest.id)
          AND field.field_id = kv.key
        LIMIT 1
    ) meta ON true
    LEFT JOIN t_form_definition_version version
      ON version.id = COALESCE(r.form_definition_version_id, snapshot.id, latest.id)
    WHERE d.status = 'SUBMITTED'
      AND meta.field_type = 'checklist'
      AND jsonb_typeof(kv.value) = 'array'
), items AS (
    SELECT rows.*, item.value AS item_value, item.ordinality::INT AS item_order
    FROM rows
    CROSS JOIN LATERAL jsonb_array_elements(rows.raw_value)
        WITH ORDINALITY AS item(value, ordinality)
)
SELECT items.data_id,
       items.form_def_id,
       items.form_code,
       items.form_name,
       items.form_deleted,
       items.business_no,
       items.data_status,
       items.approval_status,
       items.started_at,
       items.finished_at,
       items.submitter,
       items.form_def_version AS form_version,
       items.field_id,
       items.field_label,
       items.item_order,
       CASE WHEN jsonb_typeof(items.item_value) = 'object'
            THEN items.item_value ->> 'id' END AS item_id,
       COALESCE(NULLIF(item_label.label, ''),
                NULLIF(items.item_value ->> 'name', ''),
                items.item_value ->> 'id',
                items.item_value #>> '{}') AS item_name,
       CASE WHEN jsonb_typeof(items.item_value) = 'object'
            THEN items.item_value ->> 'status' END AS status,
       COALESCE(NULLIF(result_label.label, ''),
                items.item_value ->> 'status') AS status_label,
       CASE WHEN jsonb_typeof(items.item_value -> 'images') = 'array'
                 THEN items.item_value -> 'images'
            WHEN jsonb_typeof(items.item_value -> 'photos') = 'array'
                 THEN items.item_value -> 'photos'
            ELSE '[]'::jsonb END AS images,
       COALESCE(NULLIF(items.item_value ->> 'description', ''),
                items.item_value ->> 'remark') AS description
FROM items
LEFT JOIN LATERAL (
    WITH RECURSIVE nodes(node) AS (
        SELECT node
        FROM jsonb_array_elements(COALESCE(items.form_schema, '[]'::jsonb)) AS node
        UNION ALL
        SELECT child
        FROM nodes
        CROSS JOIN LATERAL jsonb_array_elements(
            CASE WHEN jsonb_typeof(nodes.node -> 'children') = 'array'
                 THEN nodes.node -> 'children' ELSE '[]'::jsonb END
        ) AS child
    )
    SELECT node -> 'props' AS props
    FROM nodes
    WHERE node ->> 'id' = items.field_id
    LIMIT 1
) field_props ON true
LEFT JOIN LATERAL (
    SELECT candidate ->> 'label' AS label
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(field_props.props -> 'items') = 'array'
             THEN field_props.props -> 'items' ELSE '[]'::jsonb END
    ) AS candidate
    WHERE candidate ->> 'id' = items.item_value ->> 'id'
    LIMIT 1
) item_label ON true
LEFT JOIN LATERAL (
    SELECT candidate ->> 'label' AS label
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(field_props.props -> 'results') = 'array'
             THEN field_props.props -> 'results' ELSE '[]'::jsonb END
    ) AS candidate
    WHERE candidate ->> 'id' = items.item_value ->> 'status'
       OR candidate ->> 'value' = items.item_value ->> 'status'
    LIMIT 1
) result_label ON true;
