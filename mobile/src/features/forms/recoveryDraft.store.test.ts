import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MobileFormValues } from './schema/types';
import {
  createRecoveryDraftWriter,
  readRecoveryDraft,
  shouldDiscardMismatchedRecovery,
} from './recoveryDraft.store';

describe('recoveryDraft.store', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('persists recovery values at most once per 500ms', () => {
    const writer = createRecoveryDraftWriter({
      userId: 7,
      formCode: 'leave',
      draftId: null,
      schemaVersion: 3,
    });

    writer.schedule({ reason: '第一次输入' });
    writer.schedule({ reason: '第二次输入' });

    expect(readRecoveryDraft(7, 'leave', null)).toBeNull();

    vi.advanceTimersByTime(499);
    expect(readRecoveryDraft(7, 'leave', null)).toBeNull();

    vi.advanceTimersByTime(1);
    expect(readRecoveryDraft(7, 'leave', null)?.values).toEqual({ reason: '第二次输入' });

    writer.dispose();
  });

  it('flushes pending recovery immediately when the page becomes hidden', () => {
    const writer = createRecoveryDraftWriter({
      userId: 7,
      formCode: 'leave',
      draftId: 101,
      schemaVersion: 3,
    });
    writer.schedule({ reason: '马上保存' });

    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'hidden',
    });
    document.dispatchEvent(new Event('visibilitychange'));

    expect(readRecoveryDraft(7, 'leave', 101)?.values).toEqual({ reason: '马上保存' });

    writer.dispose();
  });

  it('asks before discarding recovery written for another schema version', () => {
    const confirm = vi.fn(() => true);
    const values: MobileFormValues = { reason: '旧版本内容' };

    expect(shouldDiscardMismatchedRecovery({ schemaVersion: 2, values, timestamp: 1 }, 3, confirm))
      .toBe(true);
    expect(confirm).toHaveBeenCalledWith('表单版本已更新，是否丢弃本地恢复内容？');
  });
});
