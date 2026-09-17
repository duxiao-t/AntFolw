import { describe, expect, it } from 'vitest';
import {
  CONSOLE_ENTRY,
  PAGES,
  accessKey,
  firstAccessiblePath,
  navToMenuData,
  pageAllowed,
} from '../registry';

const readCodes = (key: string) =>
  PAGES.find((page) => page.key === key)?.readCapabilities ?? [];

describe('page registry', () => {
  it('gates 通讯录 on all three read capabilities', () => {
    expect(readCodes('org.contacts')).toEqual([
      'org:company:read', 'org:department:read', 'org:user:read',
    ]);
    const page = PAGES.find((item) => item.key === 'org.contacts');
    if (!page) throw new Error('org.contacts page is missing');
    expect(pageAllowed(page, ['user'], ['org:company:read', 'org:user:read'])).toBe(false);
    expect(pageAllowed(page, ['user'], [CONSOLE_ENTRY, ...readCodes('org.contacts')])).toBe(true);
  });

  it('keeps 工作台 open for anyone who can enter the console', () => {
    const workplace = PAGES.find((page) => page.key === 'workplace');
    if (!workplace) throw new Error('workplace page is missing');
    expect(workplace.readCapabilities).toEqual([]);
    expect(pageAllowed(workplace, ['employee'], [CONSOLE_ENTRY])).toBe(true);
  });

  it('grants every registered page to admin and derives stable access keys', () => {
    PAGES.forEach((page) => {
      expect(pageAllowed(page, ['admin'], [])).toBe(true);
    });
    expect(accessKey('security.user-permissions')).toBe('can_security_user_permissions');
  });

  it('returns the first accessible path in registry order', () => {
    expect(firstAccessiblePath(['employee'], [CONSOLE_ENTRY])).toBe('/workplace');
    // 工作台没有额外只读能力要求，因此只要能进管理端就排在第一位
    expect(firstAccessiblePath(['auditor'], [CONSOLE_ENTRY, 'audit:event:read']))
      .toBe('/workplace');
  });

  it('skips menu entries whose pageKey is unknown to this frontend build', () => {
    const menu = navToMenuData([
      {
        pageKey: null, type: 'DIR', name: '权限与安全', icon: null,
        requiredPermissions: null, sortOrder: 10,
        children: [
          { pageKey: 'security.roles', type: 'PAGE', name: '角色管理', icon: null,
            requiredPermissions: null, sortOrder: 10, children: [] },
          { pageKey: 'ghost.page', type: 'PAGE', name: '幽灵页', icon: null,
            requiredPermissions: null, sortOrder: 20, children: [] },
        ],
      },
    ]);

    expect(menu).toHaveLength(1);
    expect(menu[0].children?.map((item) => item.path)).toEqual(['/security/roles']);
  });
});
