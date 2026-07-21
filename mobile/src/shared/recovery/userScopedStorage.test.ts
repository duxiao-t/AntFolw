import { describe, expect, it } from 'vitest';
import {
  buildRecoveryKey,
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
});
