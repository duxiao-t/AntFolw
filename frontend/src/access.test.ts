import { describe, expect, it } from 'vitest';
import access from './access';
import { CONSOLE_ENTRY, PAGES, accessKey } from './pages/registry';

const currentUser = (roles: string[], permissions: string[]) =>
  ({ roles, permissions }) as any;

describe('access', () => {
  it('grants every page and capability to admin implicitly', () => {
    const result = access({ currentUser: currentUser(['admin'], []) });
    expect(result.canAdmin).toBe(true);
    expect(result.canEnterConsole).toBe(true);
    PAGES.forEach((page) => expect(result[accessKey(page.key)]).toBe(true));
    expect(result.canManageBackup).toBe(true);
  });

  it('derives page visibility from capabilities (employee sees console + workplace only)', () => {
    const result = access({
      currentUser: currentUser(['employee'], [
        CONSOLE_ENTRY, 'form:runtime:read', 'workflow:task:read',
      ]),
    });

    expect(result.canAdmin).toBe(false);
    expect(result.canEnterConsole).toBe(true);
    expect(result[accessKey('workplace')]).toBe(true);
    expect(result[accessKey('approval.forms')]).toBe(false);
    expect(result[accessKey('settings.backup')]).toBe(false);
    expect(result.canUseTasks).toBe(true);
  });

  it('hides every console page when the account cannot enter the console', () => {
    const result = access({
      currentUser: currentUser(['employee'], ['form:runtime:read', 'workflow:task:read']),
    });

    expect(result.canEnterConsole).toBe(false);
    expect(result.canAccessWorkplace).toBe(false);
  });

  it('requires all read capabilities for 通讯录 and keeps 用户权限分配 administrator-only', () => {
    const partial = access({
      currentUser: currentUser(['user'], [CONSOLE_ENTRY, 'org:user:read']),
    });
    expect(partial[accessKey('org.contacts')]).toBe(false);

    const full = access({
      currentUser: currentUser(['user'], [
        CONSOLE_ENTRY, 'org:company:read', 'org:department:read', 'org:user:read',
      ]),
    });
    expect(full[accessKey('org.contacts')]).toBe(true);
    expect(full.canAssignRoles).toBe(false);
  });

  it('lets an approver open task detail without record-query capability', () => {
    const result = access({
      currentUser: currentUser(['user'], [
        CONSOLE_ENTRY, 'workflow:task:read', 'workflow:task:approve',
      ]),
    });

    expect(result.canUseProcessDetail).toBe(true);
    expect(result.canApproveTask).toBe(true);
    expect(result[accessKey('approval.records')]).toBe(false);
  });

  it('gates 企业微信 on the integration capability instead of company info', () => {
    const withoutIntegration = access({
      currentUser: currentUser(['user'], [CONSOLE_ENTRY, 'org:company:read']),
    });
    expect(withoutIntegration[accessKey('settings.wecom')]).toBe(false);

    const withIntegration = access({
      currentUser: currentUser(['user'], [CONSOLE_ENTRY, 'integration:wecom:manage']),
    });
    expect(withIntegration[accessKey('settings.wecom')]).toBe(true);
  });

  it('lets either 表单管理员 or 流程管理员 open the designers', () => {
    const formAdmin = access({
      currentUser: currentUser(['user'], [CONSOLE_ENTRY, 'form:definition:manage']),
    });
    expect(formAdmin.canDesigner).toBe(true);

    const workflowAdmin = access({
      currentUser: currentUser(['user'], [CONSOLE_ENTRY, 'workflow:definition:manage']),
    });
    expect(workflowAdmin.canDesigner).toBe(true);
    expect(workflowAdmin.canDesignProcess).toBe(true);

    const unrelated = access({
      currentUser: currentUser(['user'], [CONSOLE_ENTRY, 'workflow:task:read']),
    });
    expect(unrelated.canDesigner).toBe(false);
  });
});
