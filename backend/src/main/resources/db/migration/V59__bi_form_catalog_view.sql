-- BI 表单清单：一行 = 一个表单版本快照，回答「现在有哪些表单、有哪些版本」。
-- 第三方先在这里拿 version_id / form_def_id，再 join v_form_field_catalog、
-- v_form_option_catalog 解析字段与选项；v_form_ledger 也按 form_def_id 关联。
-- 不暴露原始 schema/settings JSONB——解析已固化进两张字典视图，BI 不自己啃 JSON。
CREATE OR REPLACE VIEW v_form_catalog AS
SELECT v.id               AS version_id,
       def.id             AS form_def_id,
       def.code           AS form_code,
       def.name           AS form_name,
       (def.deleted <> 0) AS form_deleted,
       def.status         AS form_status,
       v.version_no,
       (v.version_no = def.version) AS is_current,
       v.legacy_approximate,
       v.published_at
FROM t_form_definition_version v
JOIN t_form_definition def ON def.id = v.form_definition_id;
