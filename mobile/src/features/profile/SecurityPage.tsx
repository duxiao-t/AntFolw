import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button, SafeArea } from 'antd-mobile';
import { useNavigate } from 'react-router-dom';
import { AppPage } from '../../shared/ui/AppPage';
import { PageEmpty, PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { queryKeys } from '../../shared/api/queryKeys';
import type { DeviceSession } from '../../shared/api/types';
import { useAuthStore } from '../auth/auth.store';
import { revokeSession, useDeviceSessions } from './profile.api';

const enableWeComBinding = import.meta.env.VITE_ENABLE_WECOM_BINDING === 'true';

function formatPlatform(platform: DeviceSession['platform']) {
  return platform === 'wecom' ? '企业微信' : '浏览器';
}

function clearUserRecoveryDrafts(userId: number | undefined): void {
  if (!userId) return;
  localStorage.removeItem(`antflow-mobile:drafts:${userId}`);
  const recoveryPrefix = `af:recovery:${userId}:`;
  const recoveryKeys = Array.from({ length: localStorage.length }, (_, index) =>
    localStorage.key(index),
  ).filter((key): key is string => Boolean(key?.startsWith(recoveryPrefix)));
  for (const key of recoveryKeys) {
    localStorage.removeItem(key);
  }
}

export function SecurityPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);
  const sessionsQuery = useDeviceSessions();

  const revokeMutation = useMutation({
    mutationFn: revokeSession,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.sessions });
    },
  });

  const logoutMutation = useMutation({
    mutationFn: async () => {
      const userId = useAuthStore.getState().user?.id;
      await logout();
      clearUserRecoveryDrafts(userId);
    },
    onSuccess: () => {
      queryClient.clear();
      navigate('/login', { replace: true });
    },
  });

  if (sessionsQuery.isPending) {
    return <PageSkeleton rows={3} />;
  }

  if (sessionsQuery.isError) {
    return (
      <PageError
        title="设备会话加载失败"
        message="请稍后重试。"
        onRetry={() => void sessionsQuery.refetch()}
      />
    );
  }

  const sessions = sessionsQuery.data ?? [];

  return (
    <AppPage title="账号与安全">
      {enableWeComBinding ? (
        <section style={sectionStyle}>
          <h2 style={sectionTitleStyle}>企业账号</h2>
          <Button fill="outline" color="primary" style={{ minHeight: 44 }}>
            绑定企业微信
          </Button>
        </section>
      ) : null}

      <section style={sectionStyle}>
        <h2 style={sectionTitleStyle}>登录设备</h2>
        {sessions.length === 0 ? (
          <PageEmpty title="暂无登录设备" />
        ) : (
          <ul style={listStyle}>
            {sessions.map((session) => (
              <li key={session.id} style={itemStyle}>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <strong style={deviceNameStyle}>{session.deviceName}</strong>
                  <p style={metaStyle}>
                    {formatPlatform(session.platform)}
                    {' · '}
                    {session.lastActiveAt}
                  </p>
                  {session.isCurrent ? <span style={badgeStyle}>当前设备</span> : null}
                </div>
                {session.isCurrent ? (
                  <Button
                    color="danger"
                    fill="outline"
                    loading={logoutMutation.isPending}
                    onClick={() => logoutMutation.mutate()}
                    style={{ minHeight: 44 }}
                  >
                    退出当前设备
                  </Button>
                ) : (
                  <Button
                    color="danger"
                    fill="outline"
                    loading={revokeMutation.isPending && revokeMutation.variables === session.id}
                    onClick={() => revokeMutation.mutate(session.id)}
                    aria-label={`移除 ${session.deviceName}`}
                    style={{ minHeight: 44 }}
                  >
                    移除
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
      <SafeArea position="bottom" />
    </AppPage>
  );
}

const sectionStyle: React.CSSProperties = {
  marginBottom: 16,
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
  padding: '12px',
};

const sectionTitleStyle: React.CSSProperties = {
  margin: '0 0 12px',
  fontSize: '0.9375rem',
  fontWeight: 600,
};

const listStyle: React.CSSProperties = {
  listStyle: 'none',
  margin: 0,
  padding: 0,
};

const itemStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '12px 0',
  borderTop: '1px solid var(--af-color-border)',
};

const deviceNameStyle: React.CSSProperties = {
  display: 'block',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const metaStyle: React.CSSProperties = {
  margin: '4px 0 0',
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

const badgeStyle: React.CSSProperties = {
  display: 'inline-block',
  marginTop: 6,
  padding: '2px 8px',
  borderRadius: 6,
  background: 'rgba(49, 163, 84, 0.12)',
  color: 'var(--af-color-success)',
  fontSize: '0.75rem',
};

export default SecurityPage;
