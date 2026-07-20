import { useState, useEffect } from 'react';
import { Form, Input, Button, SafeArea, Toast } from 'antd-mobile';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore, safeReturnUrl } from './auth.store';
import { useBranding } from '../branding/BrandProvider';
import { isApiError } from '../../shared/api/errors';

export function LoginPage() {
  const branding = useBranding();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    if (status === 'authenticated') {
      const target = safeReturnUrl(params.get('returnUrl')) ?? '/workbench';
      navigate(target, { replace: true });
    }
  }, [status, params, navigate]);

  async function handleSubmit(values: { username?: string; password?: string }): Promise<void> {
    const username = values.username ?? '';
    const password = values.password ?? '';
    setSubmitting(true);
    try {
      await login(username, password);
      const target = safeReturnUrl(params.get('returnUrl')) ?? '/workbench';
      navigate(target, { replace: true });
    } catch (error) {
      if (isApiError(error) && error.status === 401) {
        Toast.show({ icon: 'fail', content: '账号或密码错误' });
      } else {
        Toast.show({ icon: 'fail', content: '登录失败，请稍后再试' });
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <SafeArea position="top" />
      <main
        className="page page-login"
        style={{
          minHeight: '100dvh',
          padding: '24px 24px 32px',
          background: 'var(--af-color-bg, #f7f8fa)',
        }}
      >
        <header style={{ marginBottom: 24 }}>
          <div
            aria-hidden="true"
            style={{
              width: 56,
              height: 56,
              borderRadius: 12,
              background: 'var(--af-color-primary, #1677ff)',
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 24,
              fontWeight: 600,
              marginBottom: 16,
            }}
          >
            {branding.companyName.slice(0, 1)}
          </div>
          <h1 style={{ margin: '0 0 4px', fontSize: '1.5rem' }}>{branding.loginTitle}</h1>
          <p style={{ margin: 0, color: 'rgba(0,0,0,0.55)' }}>{branding.appName}</p>
        </header>

        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => {
            void handleSubmit(values as { username?: string; password?: string });
          }}
          requiredMarkStyle="asterisk"
        >
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: true, message: '请输入账号' }]}
          >
            <Input
              placeholder="请输入账号"
              autoComplete="username"
              inputMode="text"
              clearable
            />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input
              type="password"
              placeholder="请输入密码"
              autoComplete="current-password"
              clearable
            />
          </Form.Item>
          <Form.Item>
            <Button
              block
              type="submit"
              color="primary"
              loading={submitting}
              disabled={submitting}
              style={{ minHeight: 44 }}
            >
              登录
            </Button>
          </Form.Item>
        </Form>

        {branding.showLoginFooter ? (
          <footer
            style={{
              marginTop: 24,
              textAlign: 'center',
              color: 'rgba(0,0,0,0.45)',
              fontSize: 12,
            }}
          >
            {branding.footerText}
          </footer>
        ) : null}
        <SafeArea position="bottom" />
      </main>
    </>
  );
}

export default LoginPage;
