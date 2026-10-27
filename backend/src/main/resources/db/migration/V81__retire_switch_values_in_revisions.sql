-- 补齐 V80 遗漏的一类存量数据：**修订版快照里的 switch 布尔值**。
--
-- V80 把 switch 转成了 select（选项 true/false），并转换了 t_form_data.data 里的布尔值，
-- 但 t_form_data_revision.data 也是同一份值的快照，没跟着转。
-- 后果：修订版被重新读取（参与人解析、附件校验、数据更正）时，字段类型是 select（要字符串），
-- 值却是布尔，历史版本看起来像脏数据。
--
-- 表结构注意：t_form_data_revision 没有 form_def_id，要通过 form_data_id 关联到 t_form_data
-- 才能拿到表单 id（第一版写错过，这里留一行提醒）。
--
-- checksum 说明：该列**只写不校验**（全仓没有任何比对代码），所以调整 data 不会破坏完整性校验。
--
-- 判定口径与 V80 一致：字段在当前定义里是 select，且选项同时含 true / false。
-- 幂等：只处理 jsonb 布尔值，转换后变字符串，重复执行为 0 行。

WITH switch_like_fields AS (
    SELECT def.id AS form_id, node ->> 'id' AS field_id
    FROM t_form_definition def,
         jsonb_array_elements(
             CASE WHEN jsonb_typeof(def.schema) = 'array' THEN def.schema ELSE '[]'::jsonb END
         ) AS node
    WHERE node ->> 'type' = 'select'
      AND node -> 'props' -> 'options' @> '[{"value":"true"}]'::jsonb
      AND node -> 'props' -> 'options' @> '[{"value":"false"}]'::jsonb
), target AS (
    SELECT revision.id AS revision_id, revision.data, data_row.form_def_id
    FROM t_form_data_revision revision
    JOIN t_form_data data_row ON data_row.id = revision.form_data_id
)
UPDATE t_form_data_revision revision
SET data = (
    SELECT jsonb_object_agg(
               kv.key,
               CASE WHEN sf.field_id IS NOT NULL AND jsonb_typeof(kv.value) = 'boolean'
                    THEN to_jsonb(kv.value #>> '{}') ELSE kv.value END)
    FROM jsonb_each(target.data) AS kv
    LEFT JOIN switch_like_fields sf
           ON sf.form_id = target.form_def_id AND sf.field_id = kv.key
)
FROM target
WHERE revision.id = target.revision_id
  AND EXISTS (
      SELECT 1 FROM switch_like_fields sf
      WHERE sf.form_id = target.form_def_id
        AND target.data ? sf.field_id
        AND jsonb_typeof(target.data -> sf.field_id) = 'boolean'
  );

-- 顺带兜一层 t_form_data：覆盖 V80 之后新增的草稿、以及 V80 因 schema 为单对象而漏判的表单。
-- 同样幂等。
WITH switch_like_fields AS (
    SELECT def.id AS form_id, node ->> 'id' AS field_id
    FROM t_form_definition def,
         jsonb_array_elements(
             CASE WHEN jsonb_typeof(def.schema) = 'object' THEN jsonb_build_array(def.schema)
                  WHEN jsonb_typeof(def.schema) = 'array' THEN def.schema
                  ELSE '[]'::jsonb END
         ) AS node
    WHERE node ->> 'type' = 'select'
      AND node -> 'props' -> 'options' @> '[{"value":"true"}]'::jsonb
      AND node -> 'props' -> 'options' @> '[{"value":"false"}]'::jsonb
)
UPDATE t_form_data data_row
SET data = (
    SELECT jsonb_object_agg(
               kv.key,
               CASE WHEN sf.field_id IS NOT NULL AND jsonb_typeof(kv.value) = 'boolean'
                    THEN to_jsonb(kv.value #>> '{}') ELSE kv.value END)
    FROM jsonb_each(data_row.data) AS kv
    LEFT JOIN switch_like_fields sf
           ON sf.form_id = data_row.form_def_id AND sf.field_id = kv.key
)
WHERE EXISTS (
    SELECT 1 FROM switch_like_fields sf
    WHERE sf.form_id = data_row.form_def_id
      AND data_row.data ? sf.field_id
      AND jsonb_typeof(data_row.data -> sf.field_id) = 'boolean'
);
