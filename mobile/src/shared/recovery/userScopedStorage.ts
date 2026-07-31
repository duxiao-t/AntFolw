export function buildRecoveryKey(userId: number, formCode: string, draftId: number | null) {
  return `af:recovery:${userId}:${formCode}:${draftId ?? 'new'}`;
}

export function readUserScopedRecovery<T>(userId: number, formCode: string, draftId: number | null): T | null {
  const raw = localStorage.getItem(buildRecoveryKey(userId, formCode, draftId));
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

export function writeUserScopedRecovery(
  userId: number,
  formCode: string,
  draftId: number | null,
  value: unknown,
) {
  localStorage.setItem(buildRecoveryKey(userId, formCode, draftId), JSON.stringify(value));
}

export function removeUserScopedRecovery(userId: number, formCode: string, draftId: number | null) {
  localStorage.removeItem(buildRecoveryKey(userId, formCode, draftId));
}

export function clearUserScopedRecovery(userId: number) {
  const prefixes = [`af:recovery:${userId}:`, `antflow-mobile:drafts:${userId}`];
  const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index));
  keys.forEach((key) => {
    if (key && prefixes.some((prefix) => key.startsWith(prefix))) {
      localStorage.removeItem(key);
    }
  });
}
