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
  Object.entries(HIDDEN_CAPABILITIES).forEach(([key, permission]) => {
    result[key] = can(permission);
  });
  Object.entries(HIDDEN_ANY_OF_CAPABILITIES).forEach(([key, codesForEntry]) => {
    result[key] = codesForEntry.some(can);
  });
  return result;
}
