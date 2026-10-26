-- 清理公司相关权限码与菜单页。
--
-- 背景：CompanyController 与「企业基础信息」页已在单公司改造中删除，
-- org:company:read / system:company:manage 不再对应任何接口或页面。
-- 留着它们的后果：角色编辑器仍能勾选，勾了却没有任何效果；菜单若还存着
-- settings.company，点进去是 404。
--
-- 幂等：全部按条件删除，重复执行无副作用。

DELETE FROM t_role_permission
WHERE permission_code IN ('org:company:read', 'system:company:manage');

DELETE FROM t_menu WHERE page_key = 'settings.company';
