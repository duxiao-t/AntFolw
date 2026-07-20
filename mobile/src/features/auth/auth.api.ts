import { ApiError } from '../../shared/api/errors';
import { apiRequest } from '../../shared/api/http';
import type { MobileUser } from '../../shared/api/types';

export interface SessionPayload {
  accessToken: string;
  user: MobileUser;
}

export const authApi = {
  async login(username: string, password: string): Promise<SessionPayload> {
    return apiRequest<SessionPayload>(
      '/api/auth/login',
      {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      },
      { csrf: true },
    );
  },
  async refresh(): Promise<SessionPayload | null> {
    try {
      return await apiRequest<SessionPayload>(
        '/api/auth/refresh',
        { method: 'POST' },
        { csrf: true },
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return null;
      }
      throw error;
    }
  },
  async logout(): Promise<void> {
    await apiRequest<void>(
      '/api/auth/logout',
      { method: 'POST' },
      { csrf: true },
    );
  },
};
