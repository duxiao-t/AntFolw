-- 台账视图修正（V45 之后发现的四处口径问题）。V45 已应用、校验和冻结，只能前滚重建视图。
--
-- ① 选项字典不递归 children：嵌套（span_layout / table_list）里的下拉取不到「值→显示名」，
--    BI 里看到的是裸的选项 code。字段字典（v_form_field_catalog）早就递归了，这里补齐。
-- ② 逐项 COALESCE 混版本：原视图对 field_label / field_type / option_label 各写一次
--    COALESCE(修订版, 最新版)，可以出现「标签来自修订版、类型来自最新版」。改成先把
--    「这一行用哪个版本」定死（修订版 → 该行的 form_def_version 快照 → 最新版），再一次性 join。
-- ③ 快照缺失时用**最新版**兜底：行的 form_def_version 才是权威。拿最新版兜底会把历史单据
--    按今天的 schema 贴标签（字段改过名/改过类型就直接错）。改成先按 form_def_version 找。
-- ④ multi_select 与数组文本比较：值是 jsonb 数组，`value #>> '{}'` 得到 `["a", "b"]`，
--    永远等不上单个 option_value，所以多选的值永远解析不出显示名。改为逐元素查字典再拼接。
--    table_list 的明细值同样进不了台账：至少把它放进值白名单，别让 BI 完全看不到。

-- ① 选项字典：与字段字典同样递归 children。
CREATE OR REPLACE VIEW v_form_option_catalog AS
WITH RECURSIVE nodes AS (
    SELECT v.id AS version_id, n AS node
    FROM t_form_definition_version v
    CROSS JOIN LATERAL jsonb_array_elements(v.schema) n
  UNION ALL
    SELECT nodes.version_id, child FROM nodes
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(nodes.node->'children') = 'array'
             THEN nodes.node->'children' ELSE '[]'::jsonb END) child
)
SELECT DISTINCT version_id, node->>'id' AS field_id,
       o->>'value' AS option_value, o->>'label' AS option_label
FROM nodes
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(node->'props'->'options') = 'array'
         THEN node->'props'->'options' ELSE '[]'::jsonb END) o
WHERE COALESCE(node->>'id', '') <> '' AND COALESCE(o->>'value', '') <> '';

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
       CASE WHEN meta.field_type = 'multi_select' THEN multi.labels
            ELSE oc.option_label END AS value_label,
       CASE WHEN meta.field_type IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker',
           'table_list'
       ) THEN kv.value END AS value_json,
       CASE WHEN meta.field_type IN (
           'number', 'money', 'date', 'date_range', 'time', 'switch',
           'radio', 'checkbox', 'select', 'multi_select', 'user_picker', 'dept_picker',
           'table_list'
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
    WHERE meta.field_type = 'multi_select'
) multi ON true
LEFT JOIN v_form_option_catalog oc
       ON oc.version_id = COALESCE(r.form_definition_version_id, snapshot.id, latest.id)
      AND oc.field_id = kv.key
      AND oc.option_value = kv.value #>> '{}'
WHERE d.status = 'SUBMITTED';
