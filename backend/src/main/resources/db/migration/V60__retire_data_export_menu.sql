-- 「数据导出」不再是独立页面：入口并入「表单管理 → 提交数据」页里的「导出」按钮。
-- 前端注册表（frontend/src/pages/registry.ts）与 page-capabilities.json 同步删掉了
-- pageKey `report.export`，所以这条菜单行必须一起删：MenuService.loadTree 对每个
-- page_key 都要在能力清单里查到，否则整棵导航报 MENU_PAGE_KEY_UNKNOWN。
-- 能力点 form:data:export 保留——导出接口仍靠它鉴权，角色仍可单独授予。
DELETE FROM t_menu WHERE page_key = 'report.export';

-- 菜单版本推进，让已登录的前端重新拉导航（旧菜单里还挂着这个页面）。
UPDATE t_menu_revision SET version = version + 1, updated_at = now() WHERE id = 1;
