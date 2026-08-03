import { describe, expect, it } from 'vitest';
import { isRefreshExcludedAuthEndpoint } from './AppProviders';

describe('AppProviders auth endpoint policy', () => {
  it.each([
    ['/api/auth/login', true],
    ['/api/auth/refresh', true],
    ['/api/auth/logout', true],
    ['/api/auth/sessions', false],
    ['/api/auth/sessions/phone', false],
  ])('classifies %s as refresh-excluded: %s', (path, expected) => {
    expect(isRefreshExcludedAuthEndpoint(path)).toBe(expected);
  });
});
