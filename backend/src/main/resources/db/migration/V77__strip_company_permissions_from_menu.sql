-- 清理 t_menu.required_permissions 里已删除的权限码。
--
-- 背景：V76 清了 t_role_permission.permission_code 与 page_key='settings.company' 的菜单行，
-- 但漏了 t_menu 的这个 JSON 数组列 —— 通讯录（page_key='org.contacts'）的
-- required_permissions 仍是 ["org:company:read","org:department:read","org:user:read"]。
--
-- 运行时目前无影响：MenuService.build 对注册表内的 pageKey 用 page-capabilities.json 覆盖，
-- DB 这一列不参与可见性判定。但「菜单管理」页保存时会把整列写回，等于把已删的权限码复活。
--
-- jsonb 的 `-` 运算符对数组是"删除匹配的字符串元素"，`?` 判断字符串元素是否存在；
-- 两条都带 WHERE，重复执行无副作用。

UPDATE t_menu
SET required_permissions = required_permissions - 'org:company:read'
WHERE required_permissions ? 'org:company:read';

UPDATE t_menu
SET required_permissions = required_permissions - 'system:company:manage'
WHERE required_permissions ? 'system:company:manage';
