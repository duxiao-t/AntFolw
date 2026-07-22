import { render, screen, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '../api/errors';
import {
  mapApiErrorToView,
  MappedErrorPage,
  ForbiddenPage,
  ServerErrorPage,
  RateLimitPage,
} from '../../app/ErrorPages';
import { NetworkStatusProvider, useNetworkStatus } from './NetworkStatusProvider';
import {
  sanitizeTelemetryEvent,
  setTelemetryTransport,
  track,
  type TelemetryEvent,
} from '../telemetry/telemetry';

function Probe() {
  const { online, canWrite } = useNetworkStatus();
  return (
    <div>
      <span data-testid="online">{String(online)}</span>
      <span data-testid="can-write">{String(canWrite)}</span>
    </div>
  );
}

describe('error mapping', () => {
  it('maps 401 to refresh/login action', () => {
    const view = mapApiErrorToView(
      new ApiError(401, { code: 'UNAUTHORIZED', message: '未登录' }),
    );
    expect(view.kind).toBe('login');
    expect(view.action).toBe('refresh_login');
  });

  it('maps 403 to forbidden page', () => {
    const view = mapApiErrorToView(
      new ApiError(403, { code: 'FORBIDDEN', message: '无权' }),
    );
    expect(view.kind).toBe('forbidden');
    expect(view.title).toBe('无权访问');
  });

  it('maps 404 with resource-specific empty message', () => {
    const taskView = mapApiErrorToView(
      new ApiError(404, { code: 'NOT_FOUND', message: '' }),
      { resource: 'task' },
    );
    expect(taskView.kind).toBe('notFound');
    expect(taskView.message).toContain('任务');

    const formView = mapApiErrorToView(
      new ApiError(404, { code: 'NOT_FOUND', message: '' }),
      { resource: 'form' },
    );
    expect(formView.message).toContain('表单');
  });

  it('maps 409 to refresh prompt', () => {
    const view = mapApiErrorToView(
      new ApiError(409, { code: 'ALREADY_ACTED', message: '已处理' }),
    );
    expect(view.kind).toBe('conflict');
    expect(view.action).toBe('refresh_resource');
  });

  it('maps 422 to field/business validation', () => {
    const view = mapApiErrorToView(
      new ApiError(422, {
        code: 'VALIDATION',
        message: '校验失败',
        fieldErrors: [{ field: 'days', message: '必填' }],
      }),
    );
    expect(view.kind).toBe('validation');
    expect(view.fieldErrors?.[0]?.field).toBe('days');
    expect(view.action).toBe('show_fields');
  });

  it('maps 429 to disabled-until retryAfter', () => {
    const view = mapApiErrorToView(
      new ApiError(429, {
        code: 'RATE_LIMIT',
        message: '太频繁',
        retryAfter: 30,
      }),
    );
    expect(view.kind).toBe('rateLimit');
    expect(view.retryAfter).toBe(30);
    expect(view.action).toBe('wait_retry');
  });

  it('maps 500 to traceId server page', () => {
    const view = mapApiErrorToView(
      new ApiError(500, {
        code: 'INTERNAL',
        message: '服务器错误',
        traceId: 'trace-abc-123',
      }),
    );
    expect(view.kind).toBe('server');
    expect(view.traceId).toBe('trace-abc-123');
  });

  it('renders forbidden and server pages', () => {
    render(
      <MemoryRouter>
        <ForbiddenPage />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: '无权访问' })).toBeInTheDocument();

    render(
      <MemoryRouter>
        <ServerErrorPage traceId="t-1" />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('error-trace-id')).toHaveTextContent('t-1');

    render(
      <MemoryRouter>
        <RateLimitPage retryAfter={12} />
      </MemoryRouter>,
    );
    expect(screen.getByText(/12 秒/)).toBeInTheDocument();
  });

  it('renders mapped page for 403', () => {
    render(
      <MemoryRouter>
        <MappedErrorPage
          error={new ApiError(403, { code: 'FORBIDDEN', message: '禁止访问' })}
        />
      </MemoryRouter>,
    );
    expect(screen.getByText('禁止访问')).toBeInTheDocument();
  });
});

describe('offline network status', () => {
  beforeEach(() => {
    Object.defineProperty(window.navigator, 'onLine', {
      configurable: true,
      get: () => true,
    });
  });

  it('shows offline banner and disables writes when offline', () => {
    let online = true;
    Object.defineProperty(window.navigator, 'onLine', {
      configurable: true,
      get: () => online,
    });

    render(
      <NetworkStatusProvider>
        <Probe />
      </NetworkStatusProvider>,
    );

    expect(screen.getByTestId('online')).toHaveTextContent('true');
    expect(screen.getByTestId('can-write')).toHaveTextContent('true');
    expect(screen.queryByTestId('offline-banner-host')).not.toBeInTheDocument();

    act(() => {
      online = false;
      window.dispatchEvent(new Event('offline'));
    });

    expect(screen.getByTestId('online')).toHaveTextContent('false');
    expect(screen.getByTestId('can-write')).toHaveTextContent('false');
    expect(screen.getByTestId('offline-banner-host')).toBeInTheDocument();
    expect(screen.getByText(/网络已断开/)).toBeInTheDocument();

    act(() => {
      online = true;
      window.dispatchEvent(new Event('online'));
    });

    expect(screen.getByTestId('can-write')).toHaveTextContent('true');
    expect(screen.queryByTestId('offline-banner-host')).not.toBeInTheDocument();
  });
});

describe('privacy-safe telemetry', () => {
  afterEach(() => {
    setTelemetryTransport(null);
  });

  it('keeps only allowlisted telemetry fields', () => {
    const event = sanitizeTelemetryEvent({
      name: 'api_error',
      route: '/tasks/1',
      durationMs: 120,
      status: 500,
      code: 'INTERNAL',
      traceId: 'tr-1',
    });
    expect(event).toEqual({
      name: 'api_error',
      route: '/tasks/1',
      durationMs: 120,
      status: 500,
      code: 'INTERNAL',
      traceId: 'tr-1',
    });
  });

  it('never sends token/password/form values via transport payload', () => {
    const captured: TelemetryEvent[] = [];
    setTelemetryTransport((event) => {
      captured.push(event);
    });

    track({
      name: 'api_error',
      route: '/forms/leave',
      status: 422,
      code: 'VALIDATION',
      // password: 'secret',
      token: 'abc',
      formData: { reason: 'private' },
      comment: '审批意见',
      filename: 'a.pdf',
    } as unknown as TelemetryEvent);

    expect(captured).toHaveLength(1);
    const payload = JSON.stringify(captured[0]);
    expect(payload).not.toContain('secret');
    expect(payload).not.toContain('abc');
    expect(payload).not.toContain('private');
    expect(payload).not.toContain('审批意见');
    expect(payload).not.toContain('a.pdf');
    expect(captured[0]).toMatchObject({
      name: 'api_error',
      route: '/forms/leave',
      status: 422,
      code: 'VALIDATION',
    });
  });
});
