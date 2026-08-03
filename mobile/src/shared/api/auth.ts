/**
 * Auth glue for the typed HTTP layer. Task 4 will replace this stub with the
 * real Zustand auth store but keeps the same exported surface so http.ts does
 * not need to know which task implements the contract.
 */
export interface AuthorizationHeader {
  readonly Authorization?: string;
}

export interface AuthController {
  authorizationHeader(): AuthorizationHeader;
  refresh(): Promise<void>;
  isAuthEndpoint(path: string): boolean;
}

let controller: AuthController = {
  authorizationHeader() {
    return {};
  },
  async refresh() {
    /* replaced in Task 4 by real session rotation */
  },
  isAuthEndpoint(path: string) {
    return path.includes('/auth/');
  },
};

export function setAuthController(next: AuthController): void {
  controller = next;
}

export function getAuthController(): AuthController {
  return controller;
}

export function isAuthEndpoint(path: string): boolean {
  return controller.isAuthEndpoint(path);
}

export function readCsrfCookie(): string | null {
  if (typeof document === 'undefined') return null;
  const match = /(?:^|;\s*)antflow-csrf=([^;]+)/.exec(document.cookie);
  if (!match) return null;
  const value = match[1];
  if (!value) return null;
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
