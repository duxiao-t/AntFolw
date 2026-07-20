import { create } from 'zustand';
import { ApiError, isApiError } from '../../shared/api/errors';
import type { MobileUser } from '../../shared/api/types';
import { authApi, type SessionPayload } from './auth.api';

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous';

export interface AuthState {
  status: AuthStatus;
  accessToken: string | null;
  user: MobileUser | null;
  /**
   * Restore session on app boot. Calls the cookie-bound refresh endpoint and
   * resolves to either an authenticated or anonymous state. Anonymous resolves
   * silently (no throw) so routing can render the login page without crashing.
   */
  restore(): Promise<void>;
  login(username: string, password: string): Promise<void>;
  refresh(): Promise<void>;
  logout(): Promise<void>;
  authorizationHeader(): Record<string, string>;
  reset(): void;
}

function applySession(set: (partial: Partial<AuthState>) => void, payload: SessionPayload): void {
  set({ status: 'authenticated', accessToken: payload.accessToken, user: payload.user });
}

function applyAnonymous(set: (partial: Partial<AuthState>) => void): void {
  set({ status: 'anonymous', accessToken: null, user: null });
}

export const useAuthStore = create<AuthState>((set, get) => ({
  status: 'unknown',
  accessToken: null,
  user: null,
  async restore() {
    try {
      const payload = await authApi.refresh();
      if (payload) {
        applySession(set, payload);
      } else {
        applyAnonymous(set);
      }
    } catch (error) {
      if (isApiError(error) && error.status === 401) {
        applyAnonymous(set);
        return;
      }
      applyAnonymous(set);
    }
  },
  async login(username, password) {
    const payload = await authApi.login(username, password);
    applySession(set, payload);
  },
  async refresh() {
    const payload = await authApi.refresh();
    if (!payload) {
      applyAnonymous(set);
      throw new ApiError(401, { code: 'TOKEN_EXPIRED', message: '会话已过期' });
    }
    applySession(set, payload);
  },
  async logout() {
    try {
      await authApi.logout();
    } catch {
      /* best-effort logout */
    }
    applyAnonymous(set);
  },
  authorizationHeader(): Record<string, string> {
    const token = get().accessToken;
    if (!token) {
      return {};
    }
    return { Authorization: `Bearer ${token}` };
  },
  reset() {
    applyAnonymous(set);
  },
}));

export function isLoginPath(pathname: string): boolean {
  return pathname === '/login' || pathname.startsWith('/login?');
}

export function safeReturnUrl(candidate: string | null | undefined): string | null {
  if (!candidate) return null;
  if (!candidate.startsWith('/')) return null;
  if (candidate.startsWith('//')) return null;
  if (candidate.includes('://')) return null;
  return candidate;
}
