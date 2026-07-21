import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../auth/auth.store';
import type { MobileUser } from '../../shared/api/types';
import { buildRecoveryKey } from '../../shared/recovery/userScopedStorage';
import { DraftListPage } from './DraftListPage';

const AUTH_USER: MobileUser = {
  id: 7,
  username: 'zhangsan',
  displayName: '张三',
  roles: ['user'],
};

function setupFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/drafts') && init?.method !== 'DELETE') {
        return jsonResponse([
          {
            id: 101,
            formCode: 'leave',
            formName: '请假申请',
            formVersion: 3,
            data: { reason: '回家探亲', days: '' },
            schema: [
              { id: 'reason', type: 'text', label: '请假事由' },
              { id: 'days', type: 'number', label: '请假天数' },
            ],
            updatedAt: '2026-07-21T03:00:00+08:00',
            readOnly: false,
          },
        ]);
      }
      if (url.includes('/api/mobile/drafts/101') && init?.method === 'DELETE') {
        return new Response(null, { status: 204 });
      }
      return jsonResponse({});
    }),
  );
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
}

function renderDrafts() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/forms/drafts']}>
        <DraftListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
  vi.unstubAllGlobals();
  setupFetch();
});

describe('DraftListPage', () => {
  it('shows draft metadata, field completion and continue link', async () => {
    renderDrafts();

    expect(await screen.findByText('请假申请')).toBeInTheDocument();
    expect(screen.getByText('已填写 1/2')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '继续填写 请假申请' }))
      .toHaveAttribute('href', '/forms/leave?draftId=101');
  });

  it('confirms delete and removes server and local recovery draft', async () => {
    localStorage.setItem(buildRecoveryKey(7, 'leave', 101), '{"saved":true}');
    renderDrafts();

    await screen.findByText('请假申请');
    await userEvent.click(screen.getByRole('button', { name: '删除 请假申请' }));

    await waitFor(() => {
      const calls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
      expect(calls.some(([url, init]) =>
        String(url).includes('/api/mobile/drafts/101') && (init as RequestInit).method === 'DELETE',
      )).toBe(true);
    });
    expect(localStorage.getItem(buildRecoveryKey(7, 'leave', 101))).toBeNull();
    expect(screen.queryByText('请假申请')).not.toBeInTheDocument();
  });
});
