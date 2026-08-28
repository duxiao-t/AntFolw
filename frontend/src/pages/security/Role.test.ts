import { describe, expect, it } from 'vitest';
import {
  mergePermissionTreeSelection,
  resolvePermissionSelection,
} from './permissionDependencies';

const permissions = [
  { code: 'page.workplace', requiredPermissionCodes: ['workflow.task.read'] },
  { code: 'workflow.task.read', requiredPermissionCodes: ['page.workplace'] },
  {
    code: 'workflow.task.approve',
    requiredPermissionCodes: ['page.workplace', 'workflow.task.read'],
  },
];

describe('role permission dependencies', () => {
  it('adds task prerequisites and cleans them after the last dependent action is removed', () => {
    const selected = resolvePermissionSelection(
      ['workflow.task.approve'], new Set(), new Set(), permissions,
    );
    expect([...selected.selected]).toEqual(expect.arrayContaining([
      'workflow.task.approve', 'page.workplace', 'workflow.task.read',
    ]));

    const removed = resolvePermissionSelection(
      ['page.workplace', 'workflow.task.read'], selected.selected,
      selected.autoAdded, permissions,
    );
    expect([...removed.selected]).toEqual([]);
  });

  it('keeps valid pre-existing page and read permissions', () => {
    const selected = resolvePermissionSelection(
      ['page.workplace', 'workflow.task.read'],
      new Set(['page.workplace', 'workflow.task.read']), new Set(), permissions,
    );
    expect([...selected.selected]).toEqual(expect.arrayContaining([
      'page.workplace', 'workflow.task.read',
    ]));
  });

  it('preserves the other permission tree while checking several pages', () => {
    const merged = mergePermissionTreeSelection(
      ['page.workplace', 'page.approval.forms'],
      new Set(['page.workplace', 'workflow.task.read', 'workflow.task.approve']),
      new Set(['page.workplace', 'page.approval.forms']),
    );
    expect(merged).toEqual(expect.arrayContaining([
      'page.workplace', 'page.approval.forms',
      'workflow.task.read', 'workflow.task.approve',
    ]));
  });
});
