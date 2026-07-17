import { describe, expect, it } from 'vitest';
import {
  buildMembersCsv,
  collectDepartmentIds,
  formatGender,
  normalizeGender,
  parseMembersCsv,
  retainVisibleKeys,
  resolveDepartmentDropAction,
  resolveDepartmentDropTarget,
  summarizeSettledResults,
} from './Contacts.utils';

describe('Contacts department tree helpers', () => {
  it('collects the selected department and all descendants', () => {
    const ids = collectDepartmentIds([
      { id: 1, parentId: null },
      { id: 2, parentId: 1 },
      { id: 3, parentId: 1 },
      { id: 4, parentId: 2 },
      { id: 5, parentId: null },
    ], 1);

    expect(ids.sort((a, b) => a - b)).toEqual([1, 2, 3, 4]);
  });
});

describe('Contacts department drop helpers', () => {
  it('sorts a department before a same-parent target when dropped above it', () => {
    const result = resolveDepartmentDropAction({
      dragId: 3,
      dropId: 2,
      dropToGap: true,
      relativeDropPosition: -1,
      currentParentId: 1,
      parentById: { 1: null, 2: 1, 3: 1 },
    });

    expect(result).toEqual({ type: 'sort', targetId: 2, placement: 'BEFORE' });
  });

  it('sorts a department after a same-parent target when dropped below it', () => {
    const result = resolveDepartmentDropAction({
      dragId: 3,
      dropId: 2,
      dropToGap: true,
      relativeDropPosition: 1,
      currentParentId: 1,
      parentById: { 1: null, 2: 1, 3: 1 },
    });

    expect(result).toEqual({ type: 'sort', targetId: 2, placement: 'AFTER' });
  });

  it('moves a department under the dropped node when dropped onto a node', () => {
    const result = resolveDepartmentDropTarget({
      dragId: 3,
      dropId: 2,
      dropToGap: false,
      currentParentId: 1,
      parentById: { 1: null, 2: 1, 3: 1 },
    });

    expect(result).toEqual({ shouldMove: true, parentId: 2 });
  });

  it('moves a department to the dropped node parent when dropped into a gap', () => {
    const result = resolveDepartmentDropTarget({
      dragId: 3,
      dropId: 2,
      dropToGap: true,
      currentParentId: 4,
      parentById: { 1: null, 2: 1, 3: 4, 4: null },
    });

    expect(result).toEqual({ shouldMove: true, parentId: 1 });
  });

  it('ignores same-parent gap drops because department ordering is not persisted', () => {
    const result = resolveDepartmentDropTarget({
      dragId: 3,
      dropId: 2,
      dropToGap: true,
      currentParentId: 1,
      parentById: { 1: null, 2: 1, 3: 1 },
    });

    expect(result).toEqual({ shouldMove: false, parentId: 1, reason: 'same-parent' });
  });
});

describe('Contacts CSV helpers', () => {
  it('normalizes stored gender values to canonical form values', () => {
    expect(normalizeGender('男')).toBe('M');
    expect(normalizeGender('M')).toBe('M');
    expect(normalizeGender('女')).toBe('F');
    expect(normalizeGender('F')).toBe('F');
    expect(normalizeGender('')).toBe('');
  });

  it('formats canonical and legacy gender values with one display label', () => {
    expect(formatGender('M')).toBe('男');
    expect(formatGender('男')).toBe('男');
    expect(formatGender('F')).toBe('女');
    expect(formatGender('女')).toBe('女');
  });

  it('exports members with Chinese headers and escapes CSV cells', () => {
    const csv = buildMembersCsv([
      {
        id: 1,
        username: 'zhangsan',
        displayName: '张三,主管',
        email: 'z"s@example.com',
        phone: '13800000000',
        position: '研发',
        gender: 'M',
        deptId: 2,
      },
    ]);

    expect(csv).toBe('姓名,账号,手机,邮箱,职务,性别\r\n"张三,主管",zhangsan,13800000000,"z""s@example.com",研发,男');
  });

  it('imports Chinese-header CSV rows into the selected department', () => {
    const result = parseMembersCsv('姓名,账号,手机,邮箱,职务,性别\n李四,lisi,13900000000,lisi@example.com,产品,女', 7);

    expect(result.errors).toEqual([]);
    expect(result.rows).toEqual([
      {
        displayName: '李四',
        username: 'lisi',
        phone: '13900000000',
        email: 'lisi@example.com',
        position: '产品',
        gender: 'F',
        deptId: 7,
      },
    ]);
  });

  it('reports missing required member fields with row numbers', () => {
    const result = parseMembersCsv('姓名,账号,手机\n王五,,13900000000', 7);

    expect(result.rows).toEqual([]);
    expect(result.errors).toEqual(['第 2 行缺少账号']);
  });
});

describe('Contacts bulk action helpers', () => {
  it('keeps selected row key reference when every selected member is still visible', () => {
    const selected = [1, 2];
    const result = retainVisibleKeys(selected, new Set([1, 2, 3]));

    expect(result).toBe(selected);
  });

  it('drops selected row keys that are no longer visible', () => {
    const result = retainVisibleKeys([1, 2, 3], new Set([1, 3]));

    expect(result).toEqual([1, 3]);
  });

  it('counts fulfilled and rejected settled results', () => {
    const summary = summarizeSettledResults([
      { status: 'fulfilled', value: undefined },
      { status: 'rejected', reason: new Error('failed') },
      { status: 'fulfilled', value: { id: 1 } },
    ]);

    expect(summary).toEqual({ successCount: 2, failedCount: 1 });
  });
});
