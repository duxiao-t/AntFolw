-- BI 台账：只读视图。第三方 BI 直连库读这些视图，不读基表。
--
-- 背景：t_form_data.data 是 JSONB，键是字段 nanoid（{"terTr3TO": "x"}）、无类型，
-- 且同一列的含义随 schema 演化。BI 直接读基表既写不出可读 SQL，也算不出业务口径。
-- 所以这里把「值 → 字段标签/类型/选项显示名」的解析固化成视图，BI 只做 pivot。
--
-- 5 个视图：4 个字典 + 1 个台账长表。全部只读，且不含任何自由文本（人员字典刻意
-- 不给手机号/邮箱/账号）。授权脚本见 infra/sql/bi-readonly-role.sql。

-- 台账要 join 到流程实例取审批结果，缺这条索引。
CREATE INDEX IF NOT EXISTS ix_process_instance_form_data
    ON t_process_instance (form_data_id);

-- ① 字段字典：每个版本里「字段 id → 标签/类型」。
--    递归 children 是因为 table_list / span_layout 内部的字段否则拿不到标签、在 BI 里裸漏 nanoid。
--    当前库里还没有嵌套字段（max_depth=0），但 schema 支持嵌套，所以必须递归。
CREATE OR REPLACE VIEW v_form_field_catalog AS
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
       node->>'label' AS field_label, node->>'type' AS field_type
FROM nodes WHERE COALESCE(node->>'id', '') <> '';

-- ② 选项字典：下拉的值 → 显示名（BI 里 option_1 当维度毫无意义）
CREATE OR REPLACE VIEW v_form_option_catalog AS
SELECT DISTINCT v.id AS version_id, n->>'id' AS field_id,
       o->>'value' AS option_value, o->>'label' AS option_label
FROM t_form_definition_version v
CROSS JOIN LATERAL jsonb_array_elements(v.schema) n
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(n->'props'->'options') = 'array'
         THEN n->'props'->'options' ELSE '[]'::jsonb END) o
WHERE COALESCE(n->>'id', '') <> '' AND COALESCE(o->>'value', '') <> '';

-- ③④ 人员/部门字典：picker 字段的值是 id，名字在别的表。
--    刻意只暴露去标识所需的列——BI 不需要手机号/邮箱/账号。
CREATE OR REPLACE VIEW v_user_catalog AS
SELECT id, display_name, employee_no, dept_id FROM t_user;

CREATE OR REPLACE VIEW v_department_catalog AS
SELECT id, name, parent_id FROM t_department;

-- ⑤ 台账长表：一行 = 一次提交的一个字段值。列集合永久稳定，BI 自己 pivot。
CREATE OR REPLACE VIEW v_form_ledger AS
SELECT d.id                AS data_id,
       d.form_def_id,                          -- ← BI 的关联键
       def.code            AS form_code,       -- ← 只作展示（软删后编码可复用）
       def.name            AS form_name,
       (def.deleted <> 0)  AS form_deleted,    -- 暴露给 BI 自己筛，不静默丢行
       d.business_no,
       d.status            AS data_status,
       i.status            AS approval_status,
       i.started_at,
       i.finished_at,
       u.display_name      AS submitter,
       kv.key              AS field_id,
       COALESCE(c.field_label, cf.field_label) AS field_label,
       COALESCE(c.field_type,  cf.field_type)  AS field_type,
       COALESCE(oc.option_label, ocf.option_label) AS value_label,
       kv.value            AS value_json,
       kv.value #>> '{}'   AS value_text
FROM t_form_data d
JOIN t_form_definition def          ON def.id = d.form_def_id
LEFT JOIN t_process_instance i      ON i.form_data_id = d.id
LEFT JOIN t_user u                  ON u.id = d.created_by
LEFT JOIN t_form_data_revision r    ON r.id = d.current_revision_id
-- revision 为空（历史/草稿）时用最新发布版兜底
LEFT JOIN LATERAL (
    SELECT v.id FROM t_form_definition_version v
    WHERE v.form_definition_id = def.id ORDER BY v.version_no DESC LIMIT 1
) latest ON true
-- ⚠ 必须在引用 kv.key 的 join 之前展开，否则 Postgres 报「列不存在」
CROSS JOIN LATERAL jsonb_each(d.data) kv
LEFT JOIN v_form_field_catalog  c   ON c.version_id  = r.form_definition_version_id AND c.field_id  = kv.key
LEFT JOIN v_form_field_catalog  cf  ON cf.version_id = latest.id                     AND cf.field_id = kv.key
LEFT JOIN v_form_option_catalog oc  ON oc.version_id  = r.form_definition_version_id AND oc.field_id  = kv.key
                                   AND oc.option_value = kv.value #>> '{}'
LEFT JOIN v_form_option_catalog ocf ON ocf.version_id = latest.id                    AND ocf.field_id = kv.key
                                   AND ocf.option_value = kv.value #>> '{}'
WHERE d.status = 'SUBMITTED';
