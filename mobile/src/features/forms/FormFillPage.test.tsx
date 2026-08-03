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
    { id: 'start', type: 'date', label: '开始时间', props: { required: true } },
    { id: 'end', type: 'date', label: '结束时间', props: { required: true } },
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
      { path: '/forms/:code/self-select', element: <div>自选审批人目标页</div> },
      { path: '/forms/:code/confirm', element: <div>确认提交目标页</div> },
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

function inputByLabel(label: string) {
  const input = screen
    .getAllByLabelText(label)
    .find((element): element is HTMLInputElement | HTMLTextAreaElement =>
      element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement,
    );
  expect(input).toBeDefined();
  if (!input) {
    throw new Error(`Expected input labeled ${label}`);
  }
  return input;
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
    await userEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(await screen.findByText('请填写开始时间')).toBeInTheDocument();

    await userEvent.type(startInput, '2026-07-30');
    await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
    await userEvent.type(inputByLabel('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => {
      const calls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
      expect(calls.some(([url, init]) =>
        String(url).includes('/api/mobile/drafts')
        && (init as RequestInit).method === 'POST'
        && String((init as RequestInit).body).includes('回家探亲'),
      )).toBe(true);
    });
    expect(screen.getByText((text) => text.includes('草稿已保存'))).toBeInTheDocument();
  });

  it('loads an existing draft into the form values', async () => {
    renderForm('/forms/leave?draftId=101');

    expect(await screen.findByLabelText('开始时间')).toHaveValue('2026-07-30');
  });

  it('does not load an older-version draft and offers to discard it', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/forms/leave')) {
        return jsonResponse(FORM_RESPONSE);
      }
      if (url.includes('/api/mobile/drafts/101') && init?.method !== 'DELETE') {
        return jsonResponse({
          id: 101,
          formCode: 'leave',
          formName: '请假申请',
          formVersion: 2,
          data: { start: '2026-07-30' },
          updatedAt: '2026-07-21T03:00:00+08:00',
          readOnly: false,
        });
      }
      if (url.includes('/api/mobile/drafts/101') && init?.method === 'DELETE') {
        return jsonResponse({});
      }
      if (url.includes('/api/mobile/drafts') && init?.method === 'POST') {
        return jsonResponse(102);
      }
      return jsonResponse({});
    });

    renderForm('/forms/leave?draftId=101');

    expect(await screen.findByText('表单已更新，当前草稿基于旧版本，已为你重置为空白表单。')).toBeInTheDocument();
    expect(screen.getByLabelText('开始时间')).toHaveValue('');

    await userEvent.click(screen.getByRole('button', { name: '丢弃草稿' }));

    expect(await screen.findByText('草稿已丢弃')).toBeInTheDocument();
  });

  it('keeps next action fixed at the bottom of the viewport', async () => {
    renderForm();

    const nextButton = await screen.findByRole('button', { name: '提交' });

    expect(nextButton.parentElement).toHaveClass('action-bar', 'form-fill-action-bar');
  });

  it('renders upload fields in the flat form page', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/forms/leave')) {
        return jsonResponse({
          code: 'leave',
          name: '附件表单',
          version: 1,
          schema: [
            { id: 'reason', type: 'text', label: '事由' },
            { id: 'attachments', type: 'file_upload', label: '附件' },
            { id: 'photos', type: 'image_upload', label: '图片' },
          ],
          settings: { workflowEnabled: false },
        });
      }
      if (url.includes('/api/mobile/drafts') && init?.method === 'POST') {
        return jsonResponse(102);
      }
      return jsonResponse({});
    });

    renderForm();

    expect(await screen.findByLabelText('事由')).toBeInTheDocument();
    expect(screen.getByLabelText('附件')).toBeInTheDocument();
    expect(screen.getByLabelText('图片')).toBeInTheDocument();
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

  it('renders all fields in one scrollable page', async () => {
    renderForm();

    expect(await screen.findByRole('heading', { name: '请假申请' })).toBeInTheDocument();
    expect(screen.getByLabelText('开始时间')).toBeInTheDocument();
    expect(screen.getByLabelText('结束时间')).toBeInTheDocument();
    expect(inputByLabel('请假事由')).toBeInTheDocument();
  });

  it('renders span-layout and fields in one flat page', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/forms/leave')) {
        return jsonResponse({
          code: 'leave',
          name: '旧表单',
          version: 1,
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
            { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
          ],
        });
      }
      if (url.includes('/api/mobile/drafts') && init?.method === 'POST') {
        return jsonResponse(102);
      }
      return jsonResponse({});
    });

    renderForm();

    expect(await screen.findByRole('heading', { name: '旧表单' })).toBeInTheDocument();
    expect(screen.getByLabelText('开始时间')).toBeInTheDocument();
    expect(screen.getByLabelText('请假事由')).toBeInTheDocument();
  });

  it('shows field errors and scrolls to the first invalid field on submit', async () => {
    renderForm();

    await userEvent.click(await screen.findByRole('button', { name: '提交' }));

    expect(await screen.findByText('请填写开始时间')).toBeInTheDocument();
  });

  it('continues to the existing self-select or confirm flow after full-form validation succeeds', async () => {
    renderForm();

    await userEvent.type(await screen.findByLabelText('开始时间'), '2026-07-30');
    await userEvent.type(screen.getByLabelText('结束时间'), '2026-07-31');
    await userEvent.type(inputByLabel('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(await screen.findByText('确认提交目标页')).toBeInTheDocument();
  });
});