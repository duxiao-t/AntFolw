import { useEffect, type PropsWithChildren } from 'react';
import { useAuthStore } from './auth.store';

/**
 * Boots the auth session once on app mount via cookie-bound refresh.
 * Keeps AuthenticatedRoute out of permanent "unknown" until restore settles.
 */
export function AuthBootstrap({ children }: PropsWithChildren) {
  const restore = useAuthStore((state) => state.restore);

  useEffect(() => {
    if (isLoginDocument()) {
      return;
    }
    void restore();
  }, [restore]);

  return children;
}

function isLoginDocument(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }
  const pathname = window.location.pathname.replace(/\/$/, '');
  return pathname === '/login' || pathname === '/mobile/login';
}

export default AuthBootstrap;
