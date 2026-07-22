import { useEffect, type PropsWithChildren } from 'react';
import { useAuthStore } from './auth.store';

/**
 * Boots the auth session once on app mount via cookie-bound refresh.
 * Keeps AuthenticatedRoute out of permanent "unknown" until restore settles.
 */
export function AuthBootstrap({ children }: PropsWithChildren) {
  const restore = useAuthStore((state) => state.restore);

  useEffect(() => {
    void restore();
  }, [restore]);

  return children;
}

export default AuthBootstrap;
