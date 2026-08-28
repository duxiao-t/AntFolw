export type GrantSelectionUser = { id: number };
export type GrantSelectionDepartment = { id: number; parentId?: number; name: string };

export type GrantDepartmentNode = {
  key: number;
  title: string;
  children: GrantDepartmentNode[];
};

export function buildGrantDepartmentTree(
  rows: GrantSelectionDepartment[],
): GrantDepartmentNode[] {
  const children = new Map<number | undefined, GrantSelectionDepartment[]>();
  rows.forEach((row) => {
    children.set(row.parentId, [...(children.get(row.parentId) ?? []), row]);
  });
  const ids = new Set(rows.map((row) => row.id));
  const roots = rows.filter((row) => !row.parentId || !ids.has(row.parentId));
  const node = (row: GrantSelectionDepartment): GrantDepartmentNode => ({
    key: row.id,
    title: row.name,
    children: (children.get(row.id) ?? []).map(node),
  });
  return roots.map(node);
}

export function updateGrantUserSelection<T extends GrantSelectionUser>(
  current: Map<number, T>,
  users: T[],
  selected: boolean,
) {
  const next = new Map(current);
  users.forEach((user) => {
    if (selected) next.set(user.id, user); else next.delete(user.id);
  });
  return next;
}
