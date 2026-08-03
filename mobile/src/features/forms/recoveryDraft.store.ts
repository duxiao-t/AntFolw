import type { MobileFormValues } from './schema/types';
import {
  readUserScopedRecovery,
  removeUserScopedRecovery,
  writeUserScopedRecovery,
} from '../../shared/recovery/userScopedStorage';

const RECOVERY_WRITE_DELAY_MS = 500;

export type RecoveryDraftSnapshot = {
  schemaVersion: number;
  values: MobileFormValues;
  timestamp: number;
};

export type RecoveryDraftWriter = {
  schedule(values: MobileFormValues): void;
  flush(): void;
  dispose(): void;
};

export function readRecoveryDraft(userId: number, formCode: string, draftId: number | null) {
  return readUserScopedRecovery<RecoveryDraftSnapshot>(userId, formCode, draftId);
}

export function writeRecoveryDraft(
  userId: number,
  formCode: string,
  draftId: number | null,
  snapshot: RecoveryDraftSnapshot,
) {
  writeUserScopedRecovery(userId, formCode, draftId, snapshot);
}

export function removeRecoveryDraft(userId: number, formCode: string, draftId: number | null) {
  removeUserScopedRecovery(userId, formCode, draftId);
}

export function shouldDiscardMismatchedRecovery(
  snapshot: RecoveryDraftSnapshot,
  schemaVersion: number,
  confirmDiscard: (message: string) => boolean = confirmDialog,
) {
  if (snapshot.schemaVersion === schemaVersion) {
    return false;
  }
  return confirmDiscard('表单版本已更新，是否丢弃本地恢复内容？');
}

function confirmDialog(message: string) {
  if (typeof window.confirm === 'function') {
    return window.confirm(message);
  }
  return true;
}

export function createRecoveryDraftWriter({
  userId,
  formCode,
  draftId,
  schemaVersion,
}: {
  userId: number;
  formCode: string;
  draftId: number | null;
  schemaVersion: number;
}): RecoveryDraftWriter {
  let pendingValues: MobileFormValues | null = null;
  let timer: number | null = null;

  function persist(values: MobileFormValues) {
    writeRecoveryDraft(userId, formCode, draftId, {
      schemaVersion,
      values,
      timestamp: Date.now(),
    });
  }

  function flush() {
    if (timer != null) {
      window.clearTimeout(timer);
      timer = null;
    }
    if (pendingValues == null) {
      return;
    }
    persist(pendingValues);
    pendingValues = null;
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'hidden') {
      flush();
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange);

  return {
    schedule(values) {
      pendingValues = values;
      if (timer != null) {
        return;
      }
      timer = window.setTimeout(() => {
        timer = null;
        if (pendingValues == null) {
          return;
        }
        persist(pendingValues);
        pendingValues = null;
      }, RECOVERY_WRITE_DELAY_MS);
    },
    flush,
    dispose() {
      if (timer != null) {
        window.clearTimeout(timer);
        timer = null;
      }
      document.removeEventListener('visibilitychange', onVisibilityChange);
    },
  };
}
