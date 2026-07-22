import { NavLink } from 'react-router-dom';

interface RouteErrorPageProps {
  title?: string;
  message?: string;
}

export function RouteErrorPage({
  title = '页面出错',
  message = '请稍后重试或返回工作台。',
}: RouteErrorPageProps) {
  return (
    <main
      role="alert"
      style={{
        padding: '24px',
        minHeight: '100dvh',
        background: 'var(--af-color-bg, #f7f8fa)',
      }}
    >
      <h1 style={{ fontSize: '1.25rem', margin: '0 0 8px' }}>{title}</h1>
      <p style={{ margin: '0 0 16px', color: 'rgba(0,0,0,0.55)' }}>{message}</p>
      <NavLink
        to="/workbench"
        style={{
          display: 'inline-block',
          padding: '8px 16px',
          minHeight: 44,
          lineHeight: '28px',
          borderRadius: 8,
          background: 'var(--af-color-primary, #0b57d0)',
          color: '#fff',
          textDecoration: 'none',
        }}
      >
        返回工作台
      </NavLink>
    </main>
  );
}

export default RouteErrorPage;
