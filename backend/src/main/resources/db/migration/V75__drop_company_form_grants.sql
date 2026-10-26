-- 清理已失效的"公司主体"表单授权行。
--
-- 背景：t_form_resource_grant 过去支持 subject_type='COMPANY'（把表单授权给整家公司）。
-- 单公司改造后这条维度从判定与编辑两侧一并移除，这些行不会再被任何 SQL 读取；
-- 留着会让"这张表单授权给了谁"的排查出现幽灵数据（看得见、不生效）。
DELETE FROM t_form_resource_grant WHERE subject_type = 'COMPANY';
