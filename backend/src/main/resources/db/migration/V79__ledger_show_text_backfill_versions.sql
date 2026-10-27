-- 补齐 V78 的口径缺口：按**版本快照**再判一次"这张表单有没有文本字段"。
--
-- V78 只看了 t_form_definition.schema（当前定义）。但台账解析字段类型用的是
-- COALESCE(修订版 → 该行的 form_def_version 快照 → 最新版)，于是存在这种情况：
-- 表单当前 schema 已经把文本字段删了/改了，历史单据的版本快照里还有，
-- 那些台账行仍然是 field_type='text'，却因为开关没开而取不到值。
-- 实测影响：表单 id=11（未命名表单0831）1 行。
--
-- 判据：只要**任一版本**的 schema 里出现 text/textarea/search，就为该表单开启。
-- 幂等：只更新尚未开启的表单，且用 || 合并，不会覆盖其他 settings 键。

WITH RECURSIVE version_nodes(form_id, node) AS (
    SELECT version.form_definition_id, node.value
    FROM t_form_definition_version version, jsonb_array_elements(version.schema) node(value)
    UNION ALL
    SELECT parent.form_id, child.value
    FROM version_nodes parent
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(parent.node -> 'children') = 'array'
             THEN parent.node -> 'children' ELSE '[]'::jsonb END) child(value)
)
UPDATE t_form_definition def
SET settings = COALESCE(def.settings, '{}'::jsonb) || '{"ledgerShowText": true}'::jsonb
WHERE def.deleted = 0
  AND COALESCE(def.settings ->> 'ledgerShowText', 'false') <> 'true'
  AND def.id IN (
      SELECT form_id FROM version_nodes
      WHERE node ->> 'type' IN ('text', 'textarea', 'search')
  );
