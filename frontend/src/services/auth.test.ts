import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => vi.fn());

vi.mock('@umijs/max', () => ({ request: requestMock }));

import { outLogin } from './auth';

describe('outLogin', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.cookie = 'antflow-csrf=csrf-token';
  });

  it('uses the real logout endpoint with the CSRF token', async () => {
    await outLogin();

    expect(requestMock).toHaveBeenCalledWith('/api/auth/logout', {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-CSRF-Token': 'csrf-token' },
      skipErrorHandler: true,
    });
  });
});
