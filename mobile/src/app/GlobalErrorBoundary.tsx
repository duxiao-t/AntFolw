import { Component, type ErrorInfo, type PropsWithChildren, type ReactNode } from 'react';
import { track } from '../shared/telemetry/telemetry';

type GlobalErrorBoundaryState = {
  hasError: boolean;
  message: string;
};

type GlobalErrorBoundaryProps = PropsWithChildren<{
  fallback?: ReactNode;
}>;

function workbenchHref(): string {
  const base = import.meta.env.BASE_URL || '/';
  return `${base.endsWith('/') ? base : `${base}/`}workbench`;
}

export class GlobalErrorBoundary extends Component<
  GlobalErrorBoundaryProps,
  GlobalErrorBoundaryState
> {
  override state: GlobalErrorBoundaryState = {
    hasError: false,
    message: '',
  };

  static getDerivedStateFromError(error: Error): GlobalErrorBoundaryState {
    return {
      hasError: true,
      message: error?.message || '页面发生未知错误',
    };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    track({
      name: 'js_exception',
      route:
        typeof window !== 'undefined'
          ? `${window.location.pathname}${window.location.search}`
          : '/',
      code: error.name,
    });
    if (import.meta.env.DEV) {
      console.error('[GlobalErrorBoundary]', error, info.componentStack);
    }
  }

  private handleRetry = () => {
    this.setState({ hasError: false, message: '' });
  };

  override render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }
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
          <p style={{ margin: 0, color: 'rgba(0,0,0,0.55)' }}>{this.state.message}</p>
          <button
            type="button"
            onClick={this.handleRetry}
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
              background: 'var(--af-color-primary, #1677ff)',
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
    return this.props.children;
  }
}

export default GlobalErrorBoundary;
