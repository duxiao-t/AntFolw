import { Link } from 'react-router-dom';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { useMobileBootstrap } from '../workbench/workbench.api';

export function ProfilePage() {
  const bootstrapQuery = useMobileBootstrap();

  if (bootstrapQuery.isPending) {
    return <PageSkeleton rows={3} />;
  }

  if (bootstrapQuery.isError) {
    return (
      <PageError
        title="个人中心加载失败"
        message="请稍后重试。"
        onRetry={() => void bootstrapQuery.refetch()}
      />
    );
  }

  const user = bootstrapQuery.data.user;

  return (
    <AppPage title="我的">
      <section style={summaryStyle} aria-label="个人信息">
        <div style={avatarStyle}>{user.displayName.slice(0, 1)}</div>
        <div style={{ minWidth: 0 }}>
          <h2 style={nameStyle}>{user.displayName}</h2>
          <p style={usernameStyle}>{user.username}</p>
        </div>
      </section>

      <nav aria-label="个人中心入口" style={navStyle}>
        <Link to="/tasks" style={itemStyle}>
          <span>待办 {bootstrapQuery.data.pendingCount}</span>
          <span aria-hidden="true">›</span>
        </Link>
        <Link to="/tasks?status=draft" style={itemStyle}>
          <span>草稿箱</span>
          <span aria-hidden="true">›</span>
        </Link>
        <Link to="/profile/security" style={itemStyle}>
          <span>账号与安全</span>
          <span aria-hidden="true">›</span>
        </Link>
      </nav>
    </AppPage>
  );
}

export default ProfilePage;

const summaryStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  marginBottom: 16,
  padding: 16,
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
};

const avatarStyle: React.CSSProperties = {
  width: 48,
  height: 48,
  borderRadius: '50%',
  display: 'grid',
  placeItems: 'center',
  background: 'var(--af-color-primary)',
  color: 'var(--af-color-on-primary)',
  fontWeight: 700,
};

const nameStyle: React.CSSProperties = {
  margin: 0,
  fontSize: '1.125rem',
  fontWeight: 600,
};

const usernameStyle: React.CSSProperties = {
  margin: '4px 0 0',
  color: 'rgba(0,0,0,0.55)',
};

const navStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
  overflow: 'hidden',
};

const itemStyle: React.CSSProperties = {
  minHeight: 48,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  padding: '0 16px',
  color: 'var(--af-color-text)',
  textDecoration: 'none',
  borderTop: '1px solid var(--af-color-border)',
};
