-- 让台账在**指定表单**上显示文本值（value_json / value_text），其余表单维持脱敏。
--
-- 背景：V49 起台账把 text/textarea/search 排除在类型白名单外，两个值列都是 NULL，
-- 这是有意的脱敏契约（见 fixtures/field-semantics/display-text.json 的
-- free-text-is-not-carried-by-the-ledger 用例）。但业务上确有表单需要 BI 直接读文本值，
-- 所以改成**表单级开关**：t_form_definition.settings.ledgerShowText = true 才放开。
--
-- 放开时两列的关系与结构化字段保持一致：
--   value_json = 原始 JSON（文本即 JSON 字符串 "QA-001"）
--   value_text = 它的文本投影（QA-001）
--
-- 安全边界：
--   1. 只对开关打开的表单生效；新建表单默认不带该键 => 仍然脱敏。
--   2. 必须带 field_type IS NOT NULL：schema 里已不存在的孤儿键（改字段留下的残留）
--      不跟着放开，否则等于把任意残留值也暴露出去。
--
-- 回滚：重新执行 V70 的 CREATE OR REPLACE VIEW 即恢复旧投影；
--       本文件里的 UPDATE 可用 settings - 'ledgerShowText' 撤销。
--
-- 为未来新表单开启（一行）：
--   UPDATE t_form_definition SET settings = settings || '{"ledgerShowText": true}'::jsonb WHERE id = <表单ID>;

-- 一、开启现有所有"含文本字段"的表单（按 schema 递归，覆盖 span_layout / table_list 内的嵌套文本）
WITH RECURSIVE schema_nodes(form_id, node) AS (
    SELECT def.id, node.value
    FROM t_form_definition def, jsonb_array_elements(def.schema) node(value)
    WHERE def.deleted = 0
    UNION ALL
    SELECT parent.form_id, child.value
    FROM schema_nodes parent
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(parent.node -> 'children') = 'array'
             THEN parent.node -> 'children' ELSE '[]'::jsonb END) child(value)
)
UPDATE t_form_definition def
SET settings = COALESCE(def.settings, '{}'::jsonb) || '{"ledgerShowText": true}'::jsonb
WHERE def.deleted = 0
  AND def.id IN (
      SELECT form_id FROM schema_nodes
      WHERE node ->> 'type' IN ('text', 'textarea', 'search')
  );

-- 二、重建视图（基于 V70，仅改两列）
CREATE OR REPLACE VIEW v_form_ledger AS
WITH ledger AS (
    SELECT d.id                AS data_id,
           d.form_def_id,
           def.code            AS form_code,
           def.name            AS form_name,
           (def.deleted <> 0)  AS form_deleted,
           COALESCE(def.settings ->> 'ledgerShowText', 'false') = 'true' AS show_text,
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
           kv.value            AS raw_value,
           COALESCE(r.form_definition_version_id, snapshot.id, latest.id) AS version_id
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
          AND version.version_no = d.form_def_version
        LIMIT 1
    ) snapshot ON true
    LEFT JOIN LATERAL (
        SELECT version.id FROM t_form_definition_version version
        WHERE version.form_definition_id = def.id
        ORDER BY version.version_no DESC, version.id DESC
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
    WHERE d.status = 'SUBMITTED'
), projected AS (
    SELECT ledger.data_id,
           ledger.form_def_id,
           ledger.form_code,
           ledger.form_name,
           ledger.form_deleted,
           ledger.business_no,
           ledger.data_status,
           ledger.approval_status,
           ledger.started_at,
           ledger.finished_at,
           ledger.submitter,
           ledger.field_id,
           ledger.field_label,
           ledger.field_type,
           ledger.value_label,
           ledger.raw_value,
           ledger.show_text,
           CASE WHEN ledger.field_type IN (
               'number', 'money', 'date', 'date_range', 'time', 'switch',
               'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker',
               'table_list', 'checklist'
           ) THEN CASE
               WHEN ledger.field_type = 'checklist'
                    AND jsonb_typeof(ledger.raw_value) = 'array'
                    THEN checklist.mapped
               ELSE ledger.raw_value
           END
               WHEN ledger.show_text AND ledger.field_type IS NOT NULL THEN ledger.raw_value
           END AS value_json
    FROM ledger
    LEFT JOIN t_form_definition_version version ON version.id = ledger.version_id
    LEFT JOIN LATERAL (
        SELECT COALESCE(
                   jsonb_agg(
                       CASE WHEN jsonb_typeof(item.value) = 'object' THEN
                           item.value
                           || CASE WHEN item.value ? 'id' THEN
                                  jsonb_build_object(
                                      'id',
                                      COALESCE(NULLIF(item_label.label, ''),
                                               item.value ->> 'id'))
                              ELSE '{}'::jsonb END
                           || CASE WHEN item.value ? 'status' THEN
                                  jsonb_build_object(
                                      'status',
                                      COALESCE(NULLIF(result_label.label, ''),
                                               item.value ->> 'status'))
                              ELSE '{}'::jsonb END
                       ELSE item.value END
                       ORDER BY item.ord
                   ),
                   '[]'::jsonb
               ) AS mapped
        FROM jsonb_array_elements(
            CASE WHEN ledger.field_type = 'checklist'
                      AND jsonb_typeof(ledger.raw_value) = 'array'
                 THEN ledger.raw_value ELSE '[]'::jsonb END
        ) WITH ORDINALITY AS item(value, ord)
        LEFT JOIN LATERAL (
            WITH RECURSIVE nodes(node) AS (
                SELECT node
                FROM jsonb_array_elements(
                    CASE WHEN ledger.field_type = 'checklist'
                         THEN COALESCE(version.schema, '[]'::jsonb)
                         ELSE '[]'::jsonb END
                ) AS node
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
            WHERE node ->> 'id' = ledger.field_id
            LIMIT 1
        ) field_props ON true
        LEFT JOIN LATERAL (
            SELECT candidate ->> 'label' AS label
            FROM jsonb_array_elements(
                CASE WHEN jsonb_typeof(field_props.props -> 'items') = 'array'
                     THEN field_props.props -> 'items' ELSE '[]'::jsonb END
            ) AS candidate
            WHERE candidate ->> 'id' = item.value ->> 'id'
            LIMIT 1
        ) item_label ON true
        LEFT JOIN LATERAL (
            SELECT candidate ->> 'label' AS label
            FROM jsonb_array_elements(
                CASE WHEN jsonb_typeof(field_props.props -> 'results') = 'array'
                     THEN field_props.props -> 'results' ELSE '[]'::jsonb END
            ) AS candidate
            WHERE candidate ->> 'id' = item.value ->> 'status'
               OR candidate ->> 'value' = item.value ->> 'status'
            LIMIT 1
        ) result_label ON true
    ) checklist ON true
)
SELECT data_id,
       form_def_id,
       form_code,
       form_name,
       form_deleted,
       business_no,
       data_status,
       approval_status,
       started_at,
       finished_at,
       submitter,
       field_id,
       field_label,
       field_type,
       value_label,
       value_json,
       CASE WHEN field_type IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker',
           'table_list', 'checklist'
       ) THEN value_json #>> '{}'
       WHEN show_text AND field_type IS NOT NULL THEN raw_value #>> '{}' END AS value_text
FROM projected;

