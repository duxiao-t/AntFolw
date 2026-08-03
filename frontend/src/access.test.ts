import { describe, expect, it } from 'vitest';
import access from './access';

describe('access', () => {
  it('should return canAdmin true when user has admin role', () => {
    const initialState = {
      currentUser: {
        userid: '1',
        name: 'Admin User',
        avatar: 'https://example.com/avatar.png',
        roles: ['admin'],
      },
    };

    const result = access(initialState);

    expect(result.canAdmin).toBe(true);
    expect(result.canDesigner).toBe(true);
  });

  it('should return canAdmin false when user has non-admin role', () => {
    const initialState = {
      currentUser: {
        userid: '2',
        name: 'Regular User',
        avatar: 'https://example.com/avatar.png',
        roles: ['user'],
      },
    };

    const result = access(initialState);

    expect(result.canAdmin).toBe(false);
    expect(result.canDesigner).toBe(false);
  });

  it('should return canAdmin false when user roles are empty', () => {
    const initialState = {
      currentUser: {
        userid: '3',
        name: 'Guest User',
        avatar: 'https://example.com/avatar.png',
        roles: [],
      },
    };

    const result = access(initialState);

    expect(result.canAdmin).toBe(false);
  });

  it('should return canAdmin false when currentUser is undefined', () => {
    const initialState = {
      currentUser: undefined,
    };

    const result = access(initialState);

    expect(result.canAdmin).toBeFalsy();
  });

  it('should return canAdmin false when initialState is undefined', () => {
    const result = access(undefined);

    expect(result.canAdmin).toBeFalsy();
  });
});
