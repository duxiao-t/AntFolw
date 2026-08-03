import type { ErrorInfo, PropsWithChildren, ReactNode } from 'react';
import { ErrorBoundary as ReactErrorBoundary, type FallbackProps } from 'react-error-boundary';
import { track } from '../shared/telemetry/telemetry';

function workbenchHref(): string {
  const base = import.meta.env.BASE_URL || '/';
  return `${base.endsWith('/') ? base : `${base}/`}workbench`;
}

function ErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  return (
    <main
      role="alert"
      style={{
        minHeight: '100dvh',
        padding: 24,
        background: 'var(--af-color-bg, #f7f8fa)',
        display: 'grid',
        gap: 12,
        alignContent: 'start',
      }}
    >
      <h1 style={{ fontSize: '1.25rem', margin: 0 }}>页面出错</h1>
      <p style={{ margin: 0, color: 'rgba(0,0,0,0.55)' }}>
        {(error as Error | null)?.message || '页面发生未知错误'}
      </p>
      <button
        type="button"
        onClick={resetErrorBoundary}
        style={{
          minHeight: 44,
          borderRadius: 8,
          border: '1px solid var(--af-color-border)',
          background: 'var(--af-color-surface)',
          cursor: 'pointer',
        }}
      >
        重试
      </button>
      <a
        href={workbenchHref()}
        style={{
          display: 'inline-block',
          padding: '8px 16px',
          minHeight: 44,
          lineHeight: '28px',
          borderRadius: 8,
          background: 'var(--af-color-primary, #0b57d0)',
          color: '#fff',
          textDecoration: 'none',
          textAlign: 'center',
        }}
      >
        返回工作台
      </a>
    </main>
  );
}

function reportError(error: unknown, info: ErrorInfo) {
  const err = error instanceof Error ? error : new Error(String(error));
  track({
    name: 'js_exception',
    route:
      typeof window !== 'undefined'
        ? `${window.location.pathname}${window.location.search}`
        : '/',
    code: err.name,
  });
  if (import.meta.env.DEV) {
    console.error('[GlobalErrorBoundary]', err, info.componentStack);
  }
}

type GlobalErrorBoundaryProps = PropsWithChildren<{
  fallback?: ReactNode;
}>;

export function GlobalErrorBoundary({ children, fallback }: GlobalErrorBoundaryProps) {
  return (
    <ReactErrorBoundary
      fallbackRender={({ error, resetErrorBoundary }) =>
        fallback ?? <ErrorFallback error={error} resetErrorBoundary={resetErrorBoundary} />
      }
      onError={reportError}
    >
      {children}
    </ReactErrorBoundary>
  );
}

export default GlobalErrorBoundary;