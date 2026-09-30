import { describe, expect, it } from 'vitest';
import {
  buildMembersCsv,
  collectDepartmentIds,
  contactsPaneMode,
  departmentPathNames,
  filterDepartmentTree,
  formatGender,
  normalizeGender,
  parseMembersCsv,
  retainVisibleKeys,
  resolveDepartmentDropAction,
  resolveDepartmentDropTarget,
  summarizeSettledResults,
} from './Contacts.utils';

describe('Contacts pane state', () => {
  it('shows cross-department search results as soon as there is a keyword', () => {
    expect(contactsPaneMode('张三', null)).toBe('search');
    expect(contactsPaneMode('张三', 4)).toBe('search');
    // 只有空白字符不算关键词：不加这层判断，敲个空格右栏就会跳成搜索结果。
    expect(contactsPaneMode('   ', 4)).toBe('members');
  });

  it('falls back to members or the hint when the keyword is cleared', () => {
    expect(contactsPaneMode('', 4)).toBe('members');
    expect(contactsPaneMode('', null)).toBe('empty');
  });
});

describe('Contacts breadcrumb from the authorized tree', () => {
  const departments = [
    { id: 1, parentId: null, name: '总公司' },
    { id: 2, parentId: 1, name: '技术部' },
    { id: 3, parentId: 2, name: '后端组' },
  ];

  it('walks parents up to the root', () => {
    expect(departmentPathNames(departments, 3)).toEqual(['总公司', '技术部', '后端组']);
  });

  it('returns nothing for an unselected or unknown department', () => {
    expect(departmentPathNames(departments, null)).toEqual([]);
    // 搜到的部门可能不在授权树里（人员权限可见、部门权限不可见）——回空而不是崩。
    expect(departmentPathNames(departments, 999)).toEqual([]);
  });

  it('does not loop forever on a corrupted parent cycle', () => {
    expect(departmentPathNames([
      { id: 1, parentId: 2, name: 'A' },
      { id: 2, parentId: 1, name: 'B' },
    ], 1)).toEqual(['B', 'A']);
  });
});

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

  it('keeps the whole subtree of a matched node', () => {
    const tree = [
      { title: '总公司', key: 1, children: [
        { title: '研发中心', key: 2, children: [
          { title: '研发一组', key: 3 },
          { title: '研发二组', key: 4 },
        ] },
        { title: '财务部', key: 5 },
      ] },
    ];

    expect(filterDepartmentTree(tree, '研发')).toEqual([
      { title: '总公司', key: 1, children: [
        // 命中的节点连同它下面**没命中**的子节点一起留下——滤掉的话部门在、点进去却是空的。
        { title: '研发中心', key: 2, children: [
          { title: '研发一组', key: 3 },
          { title: '研发二组', key: 4 },
        ] },
      ] },
    ]);
  });

  it('keeps the ancestor chain leading to a match, and nothing when there is no match', () => {
    const tree = [
      { title: '总公司', key: 1, children: [
        { title: '研发中心', key: 2, children: [{ title: '研发一组', key: 3 }] },
        { title: '财务部', key: 5 },
      ] },
    ];

    expect(filterDepartmentTree(tree, '一组')).toEqual([
      { title: '总公司', key: 1, children: [
        { title: '研发中心', key: 2, children: [{ title: '研发一组', key: 3 }] },
      ] },
    ]);
    expect(filterDepartmentTree(tree, '不存在')).toEqual([]);
    // 空关键词 = 不过滤，返回原树（不是空树）。
    expect(filterDepartmentTree(tree, '  ')).toBe(tree);
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
        employeeNo: '000001',
        username: 'zhangsan',
        displayName: '张三,主管',
        email: 'z"s@example.com',
        phone: '13800000000',
        position: '研发',
        gender: 'M',
        deptId: 2,
      },
    ]);

    expect(csv).toBe('姓名,工号,账号,手机,邮箱,职务,性别\r\n"张三,主管",000001,zhangsan,13800000000,"z""s@example.com",研发,男');
  });

  it('neutralizes spreadsheet formulas in exported member fields', () => {
    const csv = buildMembersCsv([{
      displayName: '=HYPERLINK("https://evil.example")',
      employeeNo: '000001',
      username: '+cmd',
      phone: '',
      email: ' safe@example.com',
      position: '\t@SUM(1,1)',
      gender: 'M',
      deptId: 2,
    }]);

    expect(csv).toContain('"\'=HYPERLINK(""https://evil.example"")"');
    expect(csv).toContain("'+cmd");
    expect(csv).toContain('"\'\t@SUM(1,1)"');
  });

  it('imports Chinese-header CSV rows into the selected department', () => {
    const result = parseMembersCsv('姓名,工号,账号,手机,邮箱,职务,性别\n李四,000002,lisi,13900000000,lisi@example.com,产品,女', 7);

    expect(result.errors).toEqual([]);
    expect(result.rows).toEqual([
      {
        displayName: '李四',
        employeeNo: '000002',
        username: 'lisi',
        phone: '13900000000',
        email: 'lisi@example.com',
        position: '产品',
        gender: 'F',
        deptId: 7,
      },
    ]);
  });

  it('accepts an alphanumeric enterprise WeCom account as employee number', () => {
    const result = parseMembersCsv('姓名,工号,账号\n安星州,AnXingZhou,anxingzhou', 7);
    expect(result.errors).toEqual([]);
    expect(result.rows[0]?.employeeNo).toBe('AnXingZhou');
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

describe('Contacts CSV round trip', () => {
  it('导出的文件原样导回来，值不变（含类公式值）', () => {
    const member = {
      displayName: '=SUM(A1)',
      employeeNo: '100001',
      username: 'alice',
      phone: '13800000000',
      email: 'alice@example.com',
      position: '+组长',
      gender: 'F',
      deptId: 7,
    };

    const parsed = parseMembersCsv(buildMembersCsv([member]), 7);

    expect(parsed.errors).toEqual([]);
    expect(parsed.rows).toEqual([{
      displayName: '=SUM(A1)',
      employeeNo: '100001',
      username: 'alice',
      phone: '13800000000',
      email: 'alice@example.com',
      position: '+组长',
      gender: 'F',
      deptId: 7,
    }]);
  });

  it('含逗号与引号的值也能往返', () => {
    const member = {
      displayName: '张三, "阿三"',
      employeeNo: '',
      username: 'zhangsan',
      phone: '',
      email: '',
      position: '',
      gender: 'M',
      deptId: 1,
    };

    const parsed = parseMembersCsv(buildMembersCsv([member]), 1);

    expect(parsed.rows[0].displayName).toBe('张三, "阿三"');
  });
});
