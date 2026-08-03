import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from './auth.store';

const SKELETON_STYLE: React.CSSProperties = {
  minHeight: '100dvh',
  padding: '24px',
  background: 'var(--af-color-bg, #f7f8fa)',
};

function isSafeReturnUrl(value: string | null): value is string {
  if (!value) return false;
  if (!value.startsWith('/')) return false;
  if (value.startsWith('//')) return false;
  if (value.includes('://')) return false;
  return true;
}

export function AuthenticatedRoute() {
  const status = useAuthStore((state) => state.status);
  const location = useLocation();

  if (status === 'unknown') {
    return (
      <div
        role="status"
        aria-live="polite"
        aria-busy="true"
        style={SKELETON_STYLE}
      >
        <p>加载中…</p>
      </div>
    );
  }

  if (status === 'anonymous') {
    const candidate = `${location.pathname}${location.search}`;
    if (!isSafeReturnUrl(candidate)) {
      return <Navigate to="/login" replace />;
    }
    const params = new URLSearchParams({ returnUrl: candidate });
    return <Navigate to={`/login?${params.toString()}`} replace />;
  }

  return <Outlet />;
}

export default AuthenticatedRoute;
