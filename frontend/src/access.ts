import {
  CONSOLE_ENTRY,
  HIDDEN_CAPABILITIES,
  HIDDEN_ANY_OF_CAPABILITIES,
  PAGES,
  accessKey,
  pageAllowed,
} from './pages/registry';

type AuthorizedCurrentUser = API.CurrentUser & {
  permissions?: string[];
};

/**
 * 访问控制由能力点直接派生：新增页面只改 src/pages/registry.ts。
 * admin 角色隐式拥有全部能力；菜单可见性只看只读能力集。
 */
export default function access(
  initialState: { currentUser?: AuthorizedCurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  const roles = currentUser?.roles ?? [];
  const permissions = (currentUser as { permissions?: string[] } | undefined)?.permissions ?? [];
  const admin = roles.includes('admin');
  const can = (permission: string) => admin || permissions.includes(permission);

  const result: Record<string, boolean | ((permission: string) => boolean)> = {
    canAdmin: admin,
    can,
    canEnterConsole: can(CONSOLE_ENTRY),
  };

  PAGES.forEach((page) => {
    result[accessKey(page.key)] = pageAllowed(page, roles, permissions);
  });
  // 隐藏页同样要走管理端入口。后端这些端点一律是 @authz.console(...) / consoleAny(...)，
  // 本身就要求 CONSOLE_ACCESS；前端早先只判业务能力，导致没有入口能力的账号能打开
  // /tasks /proc /designer 这些桌面页，然后每个请求都 403，页面只剩一片报错。
  Object.entries(HIDDEN_CAPABILITIES).forEach(([key, permission]) => {
    result[key] = result.canEnterConsole && can(permission);
  });
  Object.entries(HIDDEN_ANY_OF_CAPABILITIES).forEach(([key, codesForEntry]) => {
    result[key] = result.canEnterConsole && codesForEntry.some(can);
  });
  return result;
}
