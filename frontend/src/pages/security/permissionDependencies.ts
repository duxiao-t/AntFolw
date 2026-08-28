type PermissionDependency = {
  code: string;
  requiredPermissionCodes: string[];
};

export function mergePermissionTreeSelection(
  checked: string[],
  current: Set<string>,
  activeCodes: Set<string>,
) {
  return [
    ...checked.filter((key) => !key.startsWith('group:')),
    ...[...current].filter((code) => !activeCodes.has(code)),
  ];
}

export function resolvePermissionSelection(
  checked: string[],
  previous: Set<string>,
  autoAdded: Set<string>,
  permissions: PermissionDependency[],
) {
  const next = new Set(checked.filter((key) => !key.startsWith('group:')));
  const byCode = new Map(permissions.map((permission) => [permission.code, permission]));
  const removed = new Set([...previous].filter((code) => !next.has(code)));
  let changed = true;
  while (changed) {
    changed = false;
    for (const permission of permissions) {
      if (next.has(permission.code)
        && permission.requiredPermissionCodes.some((required) => removed.has(required))) {
        next.delete(permission.code);
        removed.add(permission.code);
        changed = true;
      }
    }
  }
  const anchors = new Set([...next].filter((code) => !autoAdded.has(code)));
  const selected = new Set(anchors);
  const nextAutoAdded = new Set<string>();
  changed = true;
  while (changed) {
    changed = false;
    for (const code of [...selected]) {
      for (const required of byCode.get(code)?.requiredPermissionCodes ?? []) {
        if (!selected.has(required) && !removed.has(required)) {
          selected.add(required);
          nextAutoAdded.add(required);
          changed = true;
        }
      }
    }
  }
  return {
    selected,
    autoAdded: nextAutoAdded,
    cascaded: [...next].some((code) => !selected.has(code)),
  };
}
