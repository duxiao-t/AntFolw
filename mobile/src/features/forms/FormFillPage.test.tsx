import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../auth/auth.store';
import type { MobileUser } from '../../shared/api/types';
import { writeRecoveryDraft } from './recoveryDraft.store';
import { FormFillPage } from './FormFillPage';

const AUTH_USER: MobileUser = {
  id: 7,
  username: 'zhangsan',
  displayName: '张三',
  roles: ['user'],
};

const FORM_RESPONSE = {
  code: 'leave',
  name: '请假申请',
  version: 3,
  schema: [
    { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
  ],
};

function setupFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/forms/leave')) {
        return jsonResponse(FORM_RESPONSE);
      }
      if (url.includes('/api/mobile/drafts/101') && init?.method !== 'PUT') {
        return jsonResponse({
          id: 101,
          formCode: 'leave',
          formName: '请假申请',
          formVersion: 3,
          data: { reason: '已保存草稿' },
          updatedAt: '2026-07-21T03:00:00+08:00',
          readOnly: false,
        });
      }
      if (url.includes('/api/mobile/drafts') && init?.method === 'POST') {
        return jsonResponse(102);
      }
      if (url.includes('/api/mobile/drafts/101') && init?.method === 'PUT') {
        return jsonResponse({
          id: 101,
          formCode: 'leave',
          formName: '请假申请',
          formVersion: 3,
          data: JSON.parse(String(init.body)).data,
          updatedAt: '2026-07-21T03:05:00+08:00',
          readOnly: false,
        });
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

function renderForm(initialPath = '/forms/leave') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/forms/:code', element: <FormFillPage /> },
      { path: '/workbench', element: <div>工作台目标页</div> },
    ],
    { initialEntries: [initialPath] },
  );
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
  vi.unstubAllGlobals();
  setupFetch();
});

describe('FormFillPage', () => {
  it('loads a form, validates required fields and creates a server draft', async () => {
    renderForm();

    const input = await screen.findByLabelText('请假事由');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByText('请填写请假事由')).toBeInTheDocument();

    await userEvent.type(input, '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => {
      const calls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
      expect(calls.some(([url, init]) =>
        String(url).includes('/api/mobile/drafts')
        && (init as RequestInit).method === 'POST'
        && String((init as RequestInit).body).includes('回家探亲'),
      )).toBe(true);
    });
    expect(screen.getByRole('status')).toHaveTextContent('草稿已保存');
  });

  it('loads an existing draft into the form values', async () => {
    renderForm('/forms/leave?draftId=101');

    expect(await screen.findByLabelText('请假事由')).toHaveValue('已保存草稿');
  });

  it('keeps next action fixed at the bottom of the viewport', async () => {
    renderForm();

    const nextButton = await screen.findByRole('button', { name: '下一步' });

    expect(nextButton.parentElement).toHaveStyle({ position: 'fixed', bottom: '0px' });
  });

  it('recovers local values for the current user when schema version matches', async () => {
    writeRecoveryDraft(7, 'leave', null, {
      schemaVersion: 3,
      values: { reason: '本地恢复内容' },
      timestamp: 1,
    });

    renderForm();

    expect(await screen.findByLabelText('请假事由')).toHaveValue('本地恢复内容');
  });

  it('opens a confirmation before navigating away with dirty values', async () => {
    renderForm();

    await userEvent.type(await screen.findByLabelText('请假事由'), '临时填写');
    await userEvent.click(screen.getByRole('button', { name: '返回' }));

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('离开表单')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '继续离开' }));

    expect(await screen.findByText('工作台目标页')).toBeInTheDocument();
  });
});
