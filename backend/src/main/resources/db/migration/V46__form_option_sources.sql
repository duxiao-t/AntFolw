-- 表单引用了哪些选项数据源。
--
-- 与 t_option_data_source_grant 正交：grant 回答「谁（用户/角色）有资格引用」，
-- 本表回答「这张表单实际用到了哪些源」。设计器的字段下拉只列本表里的源，
-- 否则下拉会随源和版本数无限变长（bindable 原来根本没用 formId）。
--
-- 键用 form_def_id 而不是 revision：t_form_definition_version 只增不删，
-- 不能靠版本删除来清理引用行。
CREATE TABLE t_form_option_source (
    form_def_id BIGINT NOT NULL REFERENCES t_form_definition(id) ON DELETE CASCADE,
    source_id   BIGINT NOT NULL REFERENCES t_option_data_source(id) ON DELETE CASCADE,
    created_by  BIGINT REFERENCES t_user(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (form_def_id, source_id)
);

CREATE INDEX ix_form_option_source_source
    ON t_form_option_source(source_id, form_def_id);

-- 回填：现存表单已经 bind 过的源必须进来，否则升级后这些字段的版本下拉会空掉。
-- 草稿 schema 与所有已发布版本快照都扫（发布版里可能有草稿已删掉的绑定）。
INSERT INTO t_form_option_source(form_def_id, source_id)
SELECT DISTINCT s.form_def_id, (ref->>'sourceId')::bigint
FROM (
    SELECT id AS form_def_id, schema FROM t_form_definition
    UNION ALL
    SELECT form_definition_id, schema FROM t_form_definition_version
) s
CROSS JOIN LATERAL jsonb_path_query(s.schema, '$.**.props.optionSource') ref
JOIN t_option_data_source src ON src.id = (ref->>'sourceId')::bigint
-- 挡掉 {} 这类空绑定：sourceId 不是数字时 ::bigint 会直接报错。
WHERE ref->>'sourceId' ~ '^[0-9]+$'
ON CONFLICT DO NOTHING;
