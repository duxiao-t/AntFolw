-- 导入去重索引去掉公司维度。
--
-- 背景：uk_form_data_import_key 原为 (form_def_id, company_id, import_key) WHERE import_key IS NOT NULL。
-- 单公司改造后写入不再记录 company_id（恒为 NULL），而 PostgreSQL 的唯一索引把 NULL 视为互不相等，
-- 于是这条约束**完全失效**——并发导入同一 import_key 会双双落库。这是实测发现的，
-- 预检（SELECT）挡不住并发，只有唯一索引能挡。
--
-- 新口径：同一表单内 import_key 唯一（单公司下本就该如此）。
DROP INDEX IF EXISTS uk_form_data_import_key;

CREATE UNIQUE INDEX uk_form_data_import_key
    ON t_form_data (form_def_id, import_key)
    WHERE import_key IS NOT NULL;
