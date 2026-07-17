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

const exportHeaders = ['姓名', '账号', '手机', '邮箱', '职务', '性别'];

const headerMap: Record<string, keyof Omit<MemberCsvItem, 'id' | 'deptId'>> = {
  姓名: 'displayName',
  displayName: 'displayName',
  name: 'displayName',
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
    if (item.displayName && item.username) {
      rows.push({
        displayName: item.displayName,
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

function escapeCsvCell(value: string): string {
  if (/[",\r\n]/.test(value)) return `"${value.replace(/"/g, '""')}"`;
  return value;
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
  return rows;
}
