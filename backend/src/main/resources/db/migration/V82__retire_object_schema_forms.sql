-- 补齐 V80 的单对象 schema 分支。
--
-- V80 只处理了 schema 为**数组**的表单；早期设计器还把 schema 存成单个根对象
-- （如 {"id":"root","type":"span_layout","children":[...]}），这类表单会漏掉，
-- 类型下线后仍带 radio/search，被 FormSchemaParser 判为 BAD_SCHEMA 打不开。
-- 实测本库命中 1 张（id=23，已删除的 smoke 表单）。
--
-- 为什么单独开一版而不是改 V80：V80 已被 Flyway 记录，改内容会导致 checksum 不匹配、
-- 后端起不来（这次就是这样踩回来的）。已应用的迁移一个字节都不能动。
--
-- 判据与 V80 相同，幂等：只处理仍含旧类型且 schema 是对象的行。
CREATE OR REPLACE FUNCTION af_map_retired_field_type_v82(node jsonb) RETURNS jsonb AS $$
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
        mapped := jsonb_set(mapped, '{props}', props || jsonb_build_object('options',
            jsonb_build_array(
                jsonb_build_object('value', 'true',  'label', COALESCE(NULLIF(props ->> 'checkedText', ''), '是')),
                jsonb_build_object('value', 'false', 'label', COALESCE(NULLIF(props ->> 'uncheckedText', ''), '否'))
            )));
    END IF;
    IF jsonb_typeof(mapped -> 'children') = 'array' THEN
        mapped := jsonb_set(mapped, '{children}', (
            SELECT jsonb_agg(af_map_retired_field_type_v82(child))
            FROM jsonb_array_elements(mapped -> 'children') AS child
        ));
    END IF;
    RETURN mapped;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

UPDATE t_form_definition def
SET schema = af_map_retired_field_type_v82(def.schema)
WHERE jsonb_typeof(def.schema) = 'object'
  AND def.schema::text ~ '"(radio|checkbox|switch|search)"';

UPDATE t_form_definition_version version
SET schema = af_map_retired_field_type_v82(version.schema)
WHERE jsonb_typeof(version.schema) = 'object'
  AND version.schema::text ~ '"(radio|checkbox|switch|search)"';

DROP FUNCTION af_map_retired_field_type_v82(jsonb);
