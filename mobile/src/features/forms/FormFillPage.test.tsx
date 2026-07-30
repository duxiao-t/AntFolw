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
    {
      id: 'time',
      type: 'span_layout',
      label: '请假时间',
      children: [
        { id: 'start', type: 'date', label: '开始时间', props: { required: true } },
        { id: 'end', type: 'date', label: '结束时间', props: { required: true } },
      ],
    },
    {
      id: 'reason-group',
      type: 'span_layout',
      label: '请假事由',
      children: [
        { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
      ],
    },
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
          data: { start: '2026-07-30', end: '2026-07-31' },
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

    const startInput = await screen.findByLabelText('开始时间');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByText('请填写开始时间')).toBeInTheDocument();

    await userEvent.type(startInput, '2026-07-30');
    await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));
    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => {
      const calls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
      expect(calls.some(([url, init]) =>
        String(url).includes('/api/mobile/drafts')
        && (init as RequestInit).method === 'POST'
        && String((init as RequestInit).body).includes('回家探亲'),
      )).toBe(true);
    });
    expect(screen.getByText('草稿已保存')).toBeInTheDocument();
  });

  it('loads an existing draft into the form values', async () => {
    renderForm('/forms/leave?draftId=101');

    expect(await screen.findByLabelText('开始时间')).toHaveValue('2026-07-30');
  });

  it('keeps next action fixed at the bottom of the viewport', async () => {
    renderForm();

    const nextButton = await screen.findByRole('button', { name: '下一步' });

    expect(nextButton.parentElement).toHaveClass('af-action-bar');
  });

  it('recovers local values for the current user when schema version matches', async () => {
    writeRecoveryDraft(7, 'leave', null, {
      schemaVersion: 3,
      values: { start: '2026-07-30' },
      timestamp: 1,
    });

    renderForm();

    expect(await screen.findByLabelText('开始时间')).toHaveValue('2026-07-30');
  });

  it('opens a confirmation before navigating away with dirty values', async () => {
    renderForm();

    await userEvent.type(await screen.findByLabelText('开始时间'), '2026-07-30');
    await userEvent.click(screen.getByRole('button', { name: '返回' }));

    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('离开表单')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '继续离开' }));

    expect(await screen.findByText('工作台目标页')).toBeInTheDocument();
  });

  it('renders one form group at a time and advances after current step is valid', async () => {
    renderForm();

    expect(await screen.findByRole('heading', { name: '请假时间' })).toBeInTheDocument();
    expect(screen.getAllByText(/1\s*\/\s*2/).length).toBeGreaterThan(0);
    expect(screen.getByText('本节 2 项，预计 40 秒')).toBeInTheDocument();
    expect(screen.queryByLabelText('请假事由')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByText('请填写开始时间')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('开始时间'), '2026-07-30');
    await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByRole('heading', { name: '请假事由' })).toBeInTheDocument();
    expect(screen.getAllByText(/2\s*\/\s*2/).length).toBeGreaterThan(0);
  });

  it('jumps back to the first step with errors during final validation', async () => {
    renderForm();

    await userEvent.type(await screen.findByLabelText('开始时间'), '2026-07-30');
    await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByText('请填写请假事由')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '请假事由' })).toBeInTheDocument();
  });
});
