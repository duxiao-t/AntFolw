import { describe, expect, it } from 'vitest';
import access from './access';

describe('access', () => {
  it('should return canAdmin true when user has admin role', () => {
    const initialState = {
      currentUser: {
        userid: '1',
        name: 'Admin User',
        avatar: 'https://example.com/avatar.png',
        roles: ['admin'],
        permissions: [],
      },
    };

    const result = access(initialState);

    expect(result.canAdmin).toBe(true);
    expect(result.canDesigner).toBe(true);
  });

  it('should return canAdmin false when user has non-admin role', () => {
    const initialState = {
      currentUser: {
        userid: '2',
        name: 'Regular User',
        avatar: 'https://example.com/avatar.png',
        roles: ['user'],
        permissions: [
          'page.workplace', 'page.approval.forms', 'page.approval.records',
          'workflow.instance.read', 'form.definition.read', 'form.definition.design',
        ],
      },
    };

    const result = access(initialState);

    expect(result.canAdmin).toBe(false);
    expect(result.canDesigner).toBe(true);
    expect(result.canReadInstances).toBe(true);
    expect(result.canManageOrg).toBe(false);
    expect(result.canAssignRoles).toBe(false);
  });

  it('delegates organization pages but keeps role assignment administrator-only', () => {
    const result = access({
      currentUser: {
        userid: '4',
        name: 'Delegated Manager',
        avatar: '',
        roles: ['manager'],
        permissions: [
          'page.org.contacts',
          'org.department.read',
          'org.user.read',
          'security.user_role.read',
        ],
      },
    });

    expect(result.canManageOrg).toBe(true);
    expect(result.canAssignRoles).toBe(false);
  });

  it('allows an approver to open task detail without record-query permission', () => {
    const result = access({
      currentUser: {
        userid: '5',
        name: 'Approver',
        avatar: '',
        roles: ['approver'],
        permissions: [
          'page.workplace',
          'workflow.task.read',
          'workflow.task.approve',
        ],
      },
    });

    expect(result.canUseTasks).toBe(true);
    expect(result.canUseProcessDetail).toBe(true);
    expect(result.canUseProcesses).toBe(false);
    expect(result.canApproveTask).toBe(true);
    expect(result.canRejectTask).toBe(false);
  });

  it('requires company management permission for WeCom settings', () => {
    const pageOnly = access({ currentUser: {
      userid: '6', name: 'Viewer', avatar: '', roles: ['viewer'],
      permissions: ['page.settings.wecom'],
    } });
    const manager = access({ currentUser: {
      userid: '7', name: 'Manager', avatar: '', roles: ['manager'],
      permissions: ['page.settings.wecom', 'org.company.manage'],
    } });

    expect(pageOnly.canManageWecom).toBe(false);
    expect(manager.canManageWecom).toBe(true);
  });

  it('should return canAdmin false when user roles are empty', () => {
    const initialState = {
      currentUser: {
        userid: '3',
        name: 'Guest User',
        avatar: 'https://example.com/avatar.png',
        roles: [],
      },
    };

    const result = access(initialState);

    expect(result.canAdmin).toBe(false);
  });

  it('should return canAdmin false when currentUser is undefined', () => {
    const initialState = {
      currentUser: undefined,
    };

    const result = access(initialState);

    expect(result.canAdmin).toBeFalsy();
  });

  it('should return canAdmin false when initialState is undefined', () => {
    const result = access(undefined);

    expect(result.canAdmin).toBeFalsy();
  });
});
