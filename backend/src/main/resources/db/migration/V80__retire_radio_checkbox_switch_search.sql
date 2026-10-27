-- 下线 radio / checkbox / switch / search 四种字段类型前的存量清理。
--
-- 背景：这四种类型可由 select / multi_select 完全覆盖（单选=下拉单选、多选=下拉多选、
-- 搜索=文本输入），switch 无实际业务使用。产品决定彻底移除，从 FieldTypeCatalog 起不再识别。
--
-- 但 schema 里仍带这四种类型的表单，在类型下线后会被 FormSchemaParser 判为非法
-- （发布校验直接 BAD_SCHEMA），连设计器都打不开。所以先做类型等价转换：
--   radio    -> select        （props.options 原样保留，值仍是标量）
--   checkbox -> multi_select  （props.options 原样保留，值仍是数组）
--   search   -> text          （值仍是字符串）
--   switch   -> select        （布尔没有对应类型，转成 是/否 两个选项，值转 'true'/'false'）
--
-- 覆盖范围：当前定义 t_form_definition.schema 与全部版本快照 t_form_definition_version.schema。
-- 版本快照也要转 —— 台账与显示按 snapshot 解析字段类型，漏了会读出未知类型。
--
-- 数据侧：radio/checkbox/search 的值形状在新类型下依然合法（标量/数组/字符串），无需改写；
-- switch 的值是布尔，需要转 'true'/'false' 才能对上 select 的选项值（实测库里无 switch 数据，
-- 这里仍写出转换以防其他环境有）。

CREATE OR REPLACE FUNCTION af_map_retired_field_type(node jsonb) RETURNS jsonb AS $$
DECLARE
    mapped jsonb := node;
    kind   text  := node ->> 'type';
    props  jsonb := COALESCE(node -> 'props', '{}'::jsonb);
BEGIN
    IF kind = 'radio' THEN
        mapped := jsonb_set(node, '{type}', '"select"');
    ELSIF kind = 'checkbox' THEN
        mapped := jsonb_set(node, '{type}', '"multi_select"');
    ELSIF kind = 'search' THEN
        mapped := jsonb_set(node, '{type}', '"text"');
    ELSIF kind = 'switch' THEN
        mapped := jsonb_set(node, '{type}', '"select"');
        -- 开关的开/关文案转成两个选项；缺省用 是/否
        mapped := jsonb_set(mapped, '{props}', props || jsonb_build_object('options',
            jsonb_build_array(
                jsonb_build_object('value', 'true',  'label', COALESCE(NULLIF(props ->> 'checkedText', ''), '是')),
                jsonb_build_object('value', 'false', 'label', COALESCE(NULLIF(props ->> 'uncheckedText', ''), '否'))
            )));
    END IF;

    -- 容器（span_layout / table_list）里的子字段一并转换
    IF jsonb_typeof(mapped -> 'children') = 'array' THEN
        mapped := jsonb_set(mapped, '{children}', (
            SELECT jsonb_agg(af_map_retired_field_type(child))
            FROM jsonb_array_elements(mapped -> 'children') AS child
        ));
    END IF;
    RETURN mapped;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 一、当前定义
UPDATE t_form_definition def
SET schema = (
    SELECT jsonb_agg(af_map_retired_field_type(node))
    FROM jsonb_array_elements(def.schema) AS node
)
WHERE jsonb_typeof(def.schema) = 'array'
  AND def.schema::text ~ '"(radio|checkbox|switch|search)"';

-- 二、版本快照
UPDATE t_form_definition_version version
SET schema = (
    SELECT jsonb_agg(af_map_retired_field_type(node))
    FROM jsonb_array_elements(version.schema) AS node
)
WHERE jsonb_typeof(version.schema) = 'array'
  AND version.schema::text ~ '"(radio|checkbox|switch|search)"';

-- 三、switch 的历史值：布尔 -> 'true'/'false'（实测无数据，防御性处理）
WITH switch_fields AS (
    SELECT version.form_definition_id AS form_id, node ->> 'id' AS field_id
    FROM t_form_definition_version version, jsonb_array_elements(version.schema) AS node
    WHERE node ->> 'type' = 'select'
      AND node -> 'props' -> 'options' @> '[{"value":"true"}]'::jsonb
      AND node -> 'props' -> 'options' @> '[{"value":"false"}]'::jsonb
)
UPDATE t_form_data data
SET data = (
    SELECT jsonb_object_agg(
               kv.key,
               CASE WHEN sf.field_id IS NOT NULL AND jsonb_typeof(kv.value) = 'boolean'
                    THEN to_jsonb(kv.value #>> '{}') ELSE kv.value END)
    FROM jsonb_each(data.data) AS kv
    LEFT JOIN switch_fields sf ON sf.form_id = data.form_def_id AND sf.field_id = kv.key
)
WHERE EXISTS (SELECT 1 FROM switch_fields sf WHERE sf.form_id = data.form_def_id);

DROP FUNCTION af_map_retired_field_type(jsonb);
