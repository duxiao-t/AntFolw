import { describe, expect, it } from 'vitest';
import {
  buildRecoveryKey,
  clearUserScopedRecovery,
  readUserScopedRecovery,
  removeUserScopedRecovery,
  writeUserScopedRecovery,
} from './userScopedStorage';

describe('userScopedStorage', () => {
  it('builds recovery keys that include user, form and draft identity', () => {
    expect(buildRecoveryKey(7, 'leave', null)).toBe('af:recovery:7:leave:new');
    expect(buildRecoveryKey(7, 'leave', 101)).toBe('af:recovery:7:leave:101');
  });

  it('does not expose one user recovery draft to another user', () => {
    writeUserScopedRecovery(7, 'leave', null, { values: { reason: '回家探亲' } });

    expect(readUserScopedRecovery(7, 'leave', null)).toEqual({ values: { reason: '回家探亲' } });
    expect(readUserScopedRecovery(8, 'leave', null)).toBeNull();
  });

  it('removes only the current user recovery draft', () => {
    writeUserScopedRecovery(7, 'leave', 101, { values: { reason: '用户7' } });
    writeUserScopedRecovery(8, 'leave', 101, { values: { reason: '用户8' } });

    removeUserScopedRecovery(7, 'leave', 101);

    expect(readUserScopedRecovery(7, 'leave', 101)).toBeNull();
    expect(readUserScopedRecovery(8, 'leave', 101)).toEqual({ values: { reason: '用户8' } });
  });

  it('clears all current-user recovery keys without touching another user', () => {
    writeUserScopedRecovery(7, 'leave', null, { saved: true });
    writeUserScopedRecovery(7, 'expense', 101, { saved: true });
    writeUserScopedRecovery(8, 'leave', null, { saved: true });
    localStorage.setItem('antflow-mobile:drafts:7', '{"saved":true}');
    localStorage.setItem('antflow-mobile:drafts:8', '{"saved":true}');

    clearUserScopedRecovery(7);

    expect(readUserScopedRecovery(7, 'leave', null)).toBeNull();
    expect(readUserScopedRecovery(7, 'expense', 101)).toBeNull();
    expect(localStorage.getItem('antflow-mobile:drafts:7')).toBeNull();
    expect(readUserScopedRecovery(8, 'leave', null)).toEqual({ saved: true });
    expect(localStorage.getItem('antflow-mobile:drafts:8')).toBe('{"saved":true}');
  });
});
