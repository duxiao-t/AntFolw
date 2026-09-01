import {
  AuditOutlined,
  LockOutlined,
  MonitorOutlined,
  SafetyCertificateOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Alert, App, Button, Checkbox, Form, Input } from 'antd';
import { Helmet, request, useIntl, useModel } from '@umijs/max';
import { createStyles } from 'antd-style';
import React, { startTransition, useState } from 'react';
import { useEffect } from 'react';
import Settings from '../../../../config/defaultSettings';
import './login.css';

/** Only same-origin relative paths are accepted after login. */
export const getSafeRedirectUrl = (redirect: string | null): string => {
  if (!redirect?.startsWith('/') || redirect.startsWith('//')) return '/';
  try {
    const parsed = new URL(redirect, window.location.origin);
    if (parsed.origin !== window.location.origin) return '/';
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return '/';
  }
};

const useStyles = createStyles(({ token }) => ({
  error: {
    marginBottom: 20,
    borderColor: 'var(--af-color-danger)',
    background: 'var(--af-color-danger-soft)',
    color: 'var(--af-color-danger)',
    '& .ant-alert-message': { color: 'var(--af-color-danger)' },
  },
  submit: {
    height: 48,
    marginTop: 8,
    border: 0,
    background: 'var(--af-color-primary)',
    boxShadow: '0 8px 18px rgba(11, 87, 208, 0.22)',
    '&:hover': { background: 'var(--af-color-primary-action) !important' },
  },
  input: {
    height: 46,
    borderRadius: 6,
    borderColor: token.colorBorder,
    '&:hover, &:focus': { borderColor: 'var(--af-color-primary)' },
  },
}));

const capabilities = [
  { icon: <MonitorOutlined />, title: '流程运行监控', detail: '掌握流程状态、节点进度与异常' },
  { icon: <AuditOutlined />, title: '审批任务处理', detail: '集中处理待办，保留完整审批轨迹' },
  { icon: <SafetyCertificateOutlined />, title: '组织与权限管理', detail: '按岗位与数据范围管理后台能力' },
];

const Login: React.FC = () => {
  const [userLoginState, setUserLoginState] = useState<{ status?: string }>({});
  const [submitting, setSubmitting] = useState(false);
  const { initialState, setInitialState } = useModel('@@initialState');
  const { message } = App.useApp();
  const intl = useIntl();
  const { styles } = useStyles();
  const [providers, setProviders] = useState<Array<{ code: string; displayName: string }>>([]);

  useEffect(() => {
    void request<Array<{ code: string; displayName: string }>>('/api/public/auth/providers', {
      skipErrorHandler: true,
    }).then(setProviders).catch(() => setProviders([]));
  }, []);

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      startTransition(() => {
        setInitialState((state) => ({ ...state, currentUser: userInfo }));
      });
    }
  };

  const handleSubmit = async (values: API.LoginParams) => {
    setSubmitting(true);
    setUserLoginState({});
    try {
      const result = await request<{ accessToken: string }>('/api/auth/login', {
        method: 'POST',
        data: { username: values.username, password: values.password },
      });
      localStorage.setItem('antflow-token', result.accessToken);
      message.success(intl.formatMessage({ id: 'pages.login.success', defaultMessage: '登录成功' }));
      await fetchUserInfo();
      const redirect = getSafeRedirectUrl(new URL(window.location.href).searchParams.get('redirect'));
      window.location.href = redirect;
    } catch {
      setUserLoginState({ status: 'error' });
      message.error(intl.formatMessage({ id: 'pages.login.failure', defaultMessage: '登录失败，请检查账号和密码' }));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="login-page">
      <Helmet>
        <title>AntFlow 审批管理后台{Settings.title ? ` - ${Settings.title}` : ''}</title>
      </Helmet>
      <section className="login-brand" aria-labelledby="login-brand-title">
        <div className="brand-mark">
          <img src="/logo.svg" alt="AntFlow" />
        </div>
        <p className="brand-kicker">ANTFLOW / OPERATIONS</p>
        <h1 id="login-brand-title">AntFlow 审批管理后台</h1>
        <p className="brand-description">让每一条审批链路都可见、可控、可追溯。</p>
        <div className="capability-list">
          {capabilities.map((item) => (
            <div className="capability" key={item.title}>
              <span className="capability-icon" aria-hidden="true">{item.icon}</span>
              <span>
                <strong>{item.title}</strong>
                <small>{item.detail}</small>
              </span>
            </div>
          ))}
        </div>
        <span className="brand-stamp" aria-hidden="true">AF / 01</span>
      </section>

      <section className="login-panel" aria-label="账号登录">
        <div className="login-card">
          <div className="login-heading">
            <p className="login-eyebrow">SECURE ACCESS</p>
            <h2>进入运营中心</h2>
            <p>使用企业账号登录，继续处理审批任务。</p>
          </div>
          {userLoginState.status === 'error' && (
            <Alert
              className={styles.error}
              showIcon
              type="error"
              message="账号或密码错误，请重试"
            />
          )}
          <Form<API.LoginParams>
            layout="vertical"
            requiredMark={false}
            initialValues={{ autoLogin: true }}
            onFinish={handleSubmit}
          >
            <Form.Item
              label="账号"
              name="username"
              rules={[{ required: true, message: '请输入账号' }]}
            >
              <Input
                className={styles.input}
                prefix={<UserOutlined />}
                placeholder="请输入账号"
                autoComplete="username"
              />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                className={styles.input}
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <Form.Item name="autoLogin" valuePropName="checked" style={{ marginBottom: 12 }}>
              <Checkbox>保持登录</Checkbox>
            </Form.Item>
            <Button
              className={styles.submit}
              htmlType="submit"
              type="primary"
              block
              loading={submitting}
            >
              登录运营中心
            </Button>
          </Form>
          {providers.length > 0 ? (
            <div className="login-oidc">
              <span>或使用企业身份登录</span>
              {providers.map((provider) => (
                <Button key={provider.code} block onClick={() => {
                  const redirect = getSafeRedirectUrl(new URL(window.location.href).searchParams.get('redirect'));
                  window.location.assign(`/api/public/auth/oidc/${encodeURIComponent(provider.code)}/authorize?returnUrl=${encodeURIComponent(redirect)}`);
                }}>{provider.displayName}</Button>
              ))}
            </div>
          ) : null}
          <p className="login-footnote">登录即表示你已获授权访问企业审批数据。</p>
        </div>
      </section>
    </main>
  );
};

export default Login;
