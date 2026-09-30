import type { Key } from 'react';

export interface DeptDropTargetInput {
  dragId: number;
  dropId: number;
  dropToGap: boolean;
  currentParentId: number | null | undefined;
  parentById: Record<number, number | null>;
}

export interface DeptDropTarget {
  shouldMove: boolean;
  parentId: number | null;
  reason?: 'invalid' | 'self' | 'same-parent';
}

export interface DeptDropActionInput extends DeptDropTargetInput {
  relativeDropPosition: -1 | 0 | 1 | number;
}

export type DeptDropAction =
  | { type: 'move-parent'; parentId: number | null }
  | { type: 'sort'; targetId: number; placement: 'BEFORE' | 'AFTER' }
  | { type: 'none'; reason: 'invalid' | 'self' | 'same-parent' };

export interface MemberCsvItem {
  id?: number;
  employeeNo?: string;
  username: string;
  displayName: string;
  email?: string;
  phone?: string;
  position?: string;
  gender?: string;
  deptId: number;
}

export interface MemberCsvParseResult {
  rows: Omit<MemberCsvItem, 'id'>[];
  errors: string[];
}

export interface SettledSummary {
  successCount: number;
  failedCount: number;
}

export interface DeptTreeItem {
  id: number;
  parentId: number | null;
}

/** 成员列表与人员搜索共用的分页大小：两处请求必须一致，否则表格页数和数据边界对不上。 */
export const PAGE_SIZE = 15;

export type ContactsPaneMode = 'search' | 'members' | 'empty';

/**
 * 右栏该显示什么。有关键词就显示**跨部门的人员搜索结果**（左树的过滤同时生效），
 * 没有关键词才回到"当前部门的成员"。抽成纯函数是为了让三态可测、也不散在 JSX 里。
 */
export function contactsPaneMode(keyword: string, selDeptId: number | null): ContactsPaneMode {
  if (keyword.trim()) return 'search';
  return selDeptId === null ? 'empty' : 'members';
}

/**
 * 从**已授权**的部门列表推导祖先链（面包屑用）。
 *
 * 不用 `/api/departments/{id}/path`：那条只认 `org:department:read`，而左树的可见范围是它与
 * `org:user:read` 的并集——于是会出现"树里点得到、面包屑却 403 卡住"（跨部门搜索跳转更容易撞上）。
 * 祖先本来就带着 `contextOnly` 在树里，直接推导既准又不发请求。
 */
export interface DeptPathItem {
  id: number;
  parentId?: number | null;
  name: string;
}

export function departmentPathNames(list: DeptPathItem[], deptId: number | null): string[] {
  if (deptId === null) return [];
  const byId = new Map(list.map((item) => [item.id, item]));
  const names: string[] = [];
  const guard = new Set<number>();
  let current = byId.get(deptId);
  while (current && !guard.has(current.id)) {
    guard.add(current.id);
    names.unshift(current.name);
    current = current.parentId == null ? undefined : byId.get(current.parentId);
  }
  return names;
}

const exportHeaders = ['姓名', '工号', '账号', '手机', '邮箱', '职务', '性别'];

const headerMap: Record<string, keyof Omit<MemberCsvItem, 'id' | 'deptId'>> = {
  姓名: 'displayName',
  displayName: 'displayName',
  name: 'displayName',
  工号: 'employeeNo',
  employeeNo: 'employeeNo',
  账号: 'username',
  username: 'username',
  手机: 'phone',
  phone: 'phone',
  邮箱: 'email',
  email: 'email',
  职务: 'position',
  position: 'position',
  性别: 'gender',
  gender: 'gender',
};

export function resolveDepartmentDropTarget(input: DeptDropTargetInput): DeptDropTarget {
  const { dragId, dropId, dropToGap, currentParentId, parentById } = input;
  if (!Number.isFinite(dragId) || !Number.isFinite(dropId)) {
    return { shouldMove: false, parentId: null, reason: 'invalid' };
  }
  if (dragId === dropId) {
    return { shouldMove: false, parentId: currentParentId ?? null, reason: 'self' };
  }

  const parentId = dropToGap ? parentById[dropId] ?? null : dropId;
  if ((currentParentId ?? null) === parentId) {
    return { shouldMove: false, parentId, reason: 'same-parent' };
  }

  return { shouldMove: true, parentId };
}

export function resolveDepartmentDropAction(input: DeptDropActionInput): DeptDropAction {
  const { dragId, dropId, dropToGap, currentParentId, parentById, relativeDropPosition } = input;
  if (!Number.isFinite(dragId) || !Number.isFinite(dropId)) {
    return { type: 'none', reason: 'invalid' };
  }
  if (dragId === dropId) {
    return { type: 'none', reason: 'self' };
  }
  if (!dropToGap) {
    return { type: 'move-parent', parentId: dropId };
  }
  const targetParentId = parentById[dropId] ?? null;
  if ((currentParentId ?? null) === targetParentId) {
    return {
      type: 'sort',
      targetId: dropId,
      placement: relativeDropPosition < 0 ? 'BEFORE' : 'AFTER',
    };
  }
  return { type: 'move-parent', parentId: targetParentId };
}

export function collectTreeKeys(nodes: { key: Key; children?: { key: Key; children?: any[] }[] }[]): Key[] {
  return nodes.flatMap((n) => [n.key, ...(n.children ? collectTreeKeys(n.children) : [])]);
}

export interface DeptTreeNode {
  title?: unknown;
  children?: DeptTreeNode[];
}

/**
 * 关键字过滤部门树：命中的节点保留**整棵子树**。
 *
 * 以前命中后只留也命中的子节点，搜"研发中心"会把研发一组/二组全滤掉——用户看到部门在，
 * 点进去却一个下级都没有。命中的就是"这个部门及其下面全部"。
 */
export function filterDepartmentTree<T extends DeptTreeNode>(nodes: T[], keyword: string): T[] {
  const lower = keyword.trim().toLowerCase();
  if (!lower) return nodes;
  return nodes.flatMap((node) => {
    if (String(node.title ?? '').toLowerCase().includes(lower)) return [{ ...node }];
    const children = node.children ? filterDepartmentTree(node.children as T[], lower) : [];
    return children.length ? [{ ...node, children }] : [];
  });
}

export function collectDepartmentIds(list: DeptTreeItem[], selectedId: number | null): number[] {
  if (selectedId === null) return [];
  const byParent = new Map<number | null, number[]>();
  for (const item of list) {
    const children = byParent.get(item.parentId) ?? [];
    children.push(item.id);
    byParent.set(item.parentId, children);
  }
  const result: number[] = [];
  const stack = [selectedId];
  while (stack.length) {
    const id = stack.pop();
    if (id === undefined) continue;
    result.push(id);
    stack.push(...(byParent.get(id) ?? []));
  }
  return result;
}

export function buildMembersCsv(members: MemberCsvItem[]): string {
  const rows = members.map((m) => [
    m.displayName ?? '',
    m.employeeNo ?? '',
    m.username ?? '',
    m.phone ?? '',
    m.email ?? '',
    m.position ?? '',
    formatGender(m.gender),
  ]);
  return [exportHeaders, ...rows].map((row) => row.map(escapeCsvCell).join(',')).join('\r\n');
}

export function summarizeSettledResults(results: PromiseSettledResult<unknown>[]): SettledSummary {
  const successCount = results.filter((r) => r.status === 'fulfilled').length;
  return { successCount, failedCount: results.length - successCount };
}

export function retainVisibleKeys<T extends Key>(selectedKeys: T[], visibleIds: Set<number>): T[] {
  const next = selectedKeys.filter((id) => visibleIds.has(Number(id)));
  if (next.length === selectedKeys.length) {
    return selectedKeys;
  }
  return next;
}

export function parseMembersCsv(content: string, deptId: number): MemberCsvParseResult {
  const parsed = parseCsv(content.trim().replace(/^\uFEFF/, ''));
  if (parsed.length === 0) return { rows: [], errors: ['CSV 文件为空'] };

  const headers = parsed[0].map((h) => h.trim());
  const mappedHeaders = headers.map((h) => headerMap[h]);
  const errors: string[] = [];
  const rows: Omit<MemberCsvItem, 'id'>[] = [];

  for (let i = 1; i < parsed.length; i += 1) {
    const raw = parsed[i];
    if (raw.every((cell) => !cell.trim())) continue;

    const item: Partial<Omit<MemberCsvItem, 'id'>> = { deptId };
    raw.forEach((cell, index) => {
      const field = mappedHeaders[index];
      if (!field) return;
      const value = cell.trim();
      if (field === 'gender') item.gender = normalizeGender(value);
      else item[field] = value;
    });

    const rowNumber = i + 1;
    if (!item.displayName) errors.push(`第 ${rowNumber} 行缺少姓名`);
    if (!item.username) errors.push(`第 ${rowNumber} 行缺少账号`);
    if (item.employeeNo && !/^\S{1,64}$/.test(item.employeeNo)) errors.push(`第 ${rowNumber} 行工号必须为 1 至 64 位且不能包含空白字符`);
    if (item.displayName && item.username) {
      rows.push({
        displayName: item.displayName,
        employeeNo: item.employeeNo ?? '',
        username: item.username,
        phone: item.phone ?? '',
        email: item.email ?? '',
        position: item.position ?? '',
        gender: item.gender ?? '',
        deptId,
      });
    }
  }

  return { rows: errors.length ? [] : rows, errors };
}

/** 会被 Excel 当公式执行的起始字符（前导空白也算）。 */
const FORMULA_PREFIX = /^\s*[=+\-@]/;

function escapeCsvCell(value: string): string {
  // 前导空白/控制字符 + `=+-@` 会被 Excel 当公式执行。\s 已覆盖真正会被当触发器的 \t \r，
  // 原来还写了 \u0000-\u001f 的区段，Biome 的 noControlCharactersInRegex 不接受，去掉不影响防护。
  const safe = FORMULA_PREFIX.test(value) ? `'${value}` : value;
  if (/[",\r\n]/.test(safe)) return `"${safe.replace(/"/g, '""')}"`;
  return safe;
}

/**
 * 与 escapeCsvCell 对称：把我们自己加的那个前导撇号去掉。
 * 不这么做的话「导出 → 导入」回来值就变了（`=a` 变成 `'=a`），导出的文件不能原样导回。
 */
function unescapeCsvCell(value: string): string {
  return value.startsWith("'") && FORMULA_PREFIX.test(value.slice(1))
    ? value.slice(1)
    : value;
}

export function formatGender(value?: string): string {
  if (value === 'M' || value === '男') return '男';
  if (value === 'F' || value === '女') return '女';
  return value ?? '';
}

export function normalizeGender(value?: string): string {
  if (value === '男' || value === 'M') return 'M';
  if (value === '女' || value === 'F') return 'F';
  return value ?? '';
}

function parseCsv(content: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;

  for (let i = 0; i < content.length; i += 1) {
    const ch = content[i];
    const next = content[i + 1];
    if (quoted) {
      if (ch === '"' && next === '"') {
        cell += '"';
        i += 1;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cell += ch;
      }
      continue;
    }

    if (ch === '"') {
      quoted = true;
    } else if (ch === ',') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else if (ch !== '\r') {
      cell += ch;
    }
  }

  row.push(cell);
  rows.push(row);
  return rows.map((cells) => cells.map(unescapeCsvCell));
}
