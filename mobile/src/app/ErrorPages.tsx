import type { CSSProperties, ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { isApiError } from '../shared/api/errors';

const pageStyle: CSSProperties = {
  minHeight: '100dvh',
  padding: 24,
  background: 'var(--af-color-bg, #f7f8fa)',
  color: 'var(--af-color-text, #202830)',
  display: 'grid',
  gap: 12,
  alignContent: 'start',
};

const metaStyle: CSSProperties = {
  margin: 0,
  color: 'rgba(0,0,0,0.55)',
};

const linkStyle: CSSProperties = {
  display: 'inline-block',
  padding: '8px 16px',
  minHeight: 44,
  lineHeight: '28px',
  borderRadius: 8,
  background: 'var(--af-color-primary, #1677ff)',
  color: '#fff',
  textDecoration: 'none',
  textAlign: 'center',
};

export type ErrorResourceKind = 'task' | 'instance' | 'form' | 'generic';

export type MappedErrorView = {
  kind:
    | 'login'
    | 'forbidden'
    | 'notFound'
    | 'conflict'
    | 'validation'
    | 'rateLimit'
    | 'server'
    | 'unknown';
  title: string;
  message: string;
  traceId?: string;
  retryAfter?: number;
  fieldErrors?: ReadonlyArray<{ field: string; message: string }>;
  /** Soft actions the UI should take (caller decides navigation). */
  action: 'refresh_login' | 'show_page' | 'refresh_resource' | 'wait_retry' | 'show_fields';
};

function notFoundMessage(resource: ErrorResourceKind): string {
  switch (resource) {
    case 'task':
      return '任务不存在或已被处理。';
    case 'instance':
      return '流程实例不存在或无权查看。';
    case 'form':
      return '表单模板不存在或已停用。';
    default:
      return '请求的资源不存在。';
  }
}

/**
 * Map HTTP/API errors to enterprise recovery UX.
 * 401 → refresh/login, 403 → forbidden, 404 → resource empty,
 * 409 → refresh prompt, 422 → field/business, 429 → retryAfter, 500 → traceId.
 */
export function mapApiErrorToView(
  error: unknown,
  options: { resource?: ErrorResourceKind } = {},
): MappedErrorView {
  const resource = options.resource ?? 'generic';

  if (isApiError(error)) {
    const { status, body } = error;
    if (status === 401) {
      return {
        kind: 'login',
        title: '登录已过期',
        message: body.message || '请重新登录后继续操作。',
        traceId: body.traceId,
        action: 'refresh_login',
      };
    }
    if (status === 403) {
      return {
        kind: 'forbidden',
        title: '无权访问',
        message: body.message || '您没有权限查看该资源。',
        traceId: body.traceId,
        action: 'show_page',
      };
    }
    if (status === 404) {
      return {
        kind: 'notFound',
        title: '资源不存在',
        message: body.message || notFoundMessage(resource),
        traceId: body.traceId,
        action: 'show_page',
      };
    }
    if (status === 409) {
      return {
        kind: 'conflict',
        title: '数据已变化',
        message: body.message || '请刷新后重试。',
        traceId: body.traceId,
        action: 'refresh_resource',
      };
    }
    if (status === 422 || status === 400) {
      return {
        kind: 'validation',
        title: '提交校验失败',
        message: body.message || '请检查表单后重试。',
        traceId: body.traceId,
        fieldErrors: body.fieldErrors,
        action: 'show_fields',
      };
    }
    if (status === 429) {
      return {
        kind: 'rateLimit',
        title: '操作过于频繁',
        message: body.message || '请稍后再试。',
        traceId: body.traceId,
        retryAfter: body.retryAfter,
        action: 'wait_retry',
      };
    }
    if (status >= 500) {
      return {
        kind: 'server',
        title: '服务暂时不可用',
        message: body.message || '系统开小差了，请稍后重试。',
        traceId: body.traceId,
        action: 'show_page',
      };
    }
    return {
      kind: 'unknown',
      title: '请求失败',
      message: body.message || error.message,
      traceId: body.traceId,
      action: 'show_page',
    };
  }

  if (error instanceof Error) {
    return {
      kind: 'unknown',
      title: '发生错误',
      message: error.message || '请稍后重试。',
      action: 'show_page',
    };
  }

  return {
    kind: 'unknown',
    title: '发生错误',
    message: '请稍后重试。',
    action: 'show_page',
  };
}

function ErrorShell({
  title,
  message,
  traceId,
  children,
}: {
  title: string;
  message: string;
  traceId?: string;
  children?: ReactNode;
}) {
  return (
    <main role="alert" style={pageStyle}>
      <h1 style={{ fontSize: '1.25rem', margin: 0 }}>{title}</h1>
      <p style={metaStyle}>{message}</p>
      {traceId ? (
        <p style={metaStyle} data-testid="error-trace-id">
          追踪编号：{traceId}
        </p>
      ) : null}
      {children}
      <NavLink to="/workbench" style={linkStyle}>
        返回工作台
      </NavLink>
    </main>
  );
}

export function ForbiddenPage({ message, traceId }: { message?: string; traceId?: string }) {
  return (
    <ErrorShell
      title="无权访问"
      message={message ?? '您没有权限查看该资源。'}
      traceId={traceId}
    />
  );
}

export function NotFoundPage({
  message,
  traceId,
  resource = 'generic',
}: {
  message?: string;
  traceId?: string;
  resource?: ErrorResourceKind;
}) {
  return (
    <ErrorShell
      title="资源不存在"
      message={message ?? notFoundMessage(resource)}
      traceId={traceId}
    />
  );
}

export function ConflictPage({
  message,
  onRefresh,
  traceId,
}: {
  message?: string;
  onRefresh?: () => void;
  traceId?: string;
}) {
  return (
    <ErrorShell
      title="数据已变化"
      message={message ?? '请刷新后重试。'}
      traceId={traceId}
    >
      {onRefresh ? (
        <button type="button" onClick={onRefresh} style={{ ...linkStyle, border: 0, cursor: 'pointer' }}>
          刷新
        </button>
      ) : null}
    </ErrorShell>
  );
}

export function RateLimitPage({
  message,
  retryAfter,
  disabledUntil,
  traceId,
}: {
  message?: string;
  retryAfter?: number;
  disabledUntil?: number;
  traceId?: string;
}) {
  const seconds =
    typeof retryAfter === 'number'
      ? retryAfter
      : typeof disabledUntil === 'number'
        ? Math.max(0, Math.ceil((disabledUntil - Date.now()) / 1000))
        : undefined;
  const detail =
    typeof seconds === 'number'
      ? `${message ?? '请稍后再试。'}（约 ${seconds} 秒后可重试）`
      : (message ?? '请稍后再试。');
  return <ErrorShell title="操作过于频繁" message={detail} traceId={traceId} />;
}

export function ServerErrorPage({ message, traceId }: { message?: string; traceId?: string }) {
  return (
    <ErrorShell
      title="服务暂时不可用"
      message={message ?? '系统开小差了，请稍后重试。'}
      traceId={traceId}
    />
  );
}

export function ValidationErrorPage({
  message,
  fieldErrors,
  traceId,
}: {
  message?: string;
  fieldErrors?: ReadonlyArray<{ field: string; message: string }>;
  traceId?: string;
}) {
  return (
    <ErrorShell
      title="提交校验失败"
      message={message ?? '请检查表单后重试。'}
      traceId={traceId}
    >
      {fieldErrors && fieldErrors.length > 0 ? (
        <ul style={{ margin: 0, paddingLeft: 18 }}>
          {fieldErrors.map((entry) => (
            <li key={`${entry.field}-${entry.message}`}>
              {entry.field}: {entry.message}
            </li>
          ))}
        </ul>
      ) : null}
    </ErrorShell>
  );
}

export function MappedErrorPage({
  error,
  resource,
  onRefresh,
}: {
  error: unknown;
  resource?: ErrorResourceKind;
  onRefresh?: () => void;
}) {
  const view = mapApiErrorToView(error, { resource });
  switch (view.kind) {
    case 'forbidden':
      return <ForbiddenPage message={view.message} traceId={view.traceId} />;
    case 'notFound':
      return <NotFoundPage message={view.message} traceId={view.traceId} resource={resource} />;
    case 'conflict':
      return (
        <ConflictPage message={view.message} traceId={view.traceId} onRefresh={onRefresh} />
      );
    case 'validation':
      return (
        <ValidationErrorPage
          message={view.message}
          fieldErrors={view.fieldErrors}
          traceId={view.traceId}
        />
      );
    case 'rateLimit':
      return (
        <RateLimitPage
          message={view.message}
          retryAfter={view.retryAfter}
          traceId={view.traceId}
        />
      );
    case 'server':
      return <ServerErrorPage message={view.message} traceId={view.traceId} />;
    case 'login':
      return (
        <ErrorShell title={view.title} message={view.message} traceId={view.traceId}>
          <NavLink to="/login" style={linkStyle}>
            重新登录
          </NavLink>
        </ErrorShell>
      );
    default:
      return <ServerErrorPage message={view.message} traceId={view.traceId} />;
  }
}

export default MappedErrorPage;
