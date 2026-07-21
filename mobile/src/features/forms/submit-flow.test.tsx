import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../auth/auth.store';
import type { MobileUser } from '../../shared/api/types';
import { buildRecoveryKey } from '../../shared/recovery/userScopedStorage';
import { FormFillPage } from './FormFillPage';
import { SelfSelectPage } from './SelfSelectPage';
import { SubmitConfirmPage } from './SubmitConfirmPage';
import { SubmitSuccessPage } from './SubmitSuccessPage';
import { useSubmitFlowStore } from './submitFlow.store';
import { ProcessDetailPage } from '../processes/ProcessDetailPage';

const AUTH_USER: MobileUser = {
  id: 7,
  username: 'zhangsan',
  displayName: '张三',
  roles: ['user'],
};

const FORM_WITHOUT_SELF_SELECT = {
  code: 'leave',
  name: '请假申请',
  version: 3,
  schema: [
    { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
    { id: 'attachments', type: 'file_upload', label: '附件' },
  ],
  process: { id: 'root', type: 'ROOT', children: null },
};

const FORM_WITH_SELF_SELECT = {
  code: 'leave',
  name: '请假申请',
  version: 3,
  schema: [
    { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
  ],
  process: {
    id: 'root',
    type: 'ROOT',
    children: {
      id: 'manager',
      type: 'APPROVAL',
      props: {
        name: '直属主管',
        assignedType: 'SELF_SELECT',
        selfSelect: { multiple: false },
        candidates: [
          { id: 21, name: '张经理' },
          { id: 22, name: '李经理' },
        ],
      },
    },
  },
};

const START_RESULT = {
  instanceId: 9001,
  formDataId: 5001,
  firstTaskIds: [3001],
};

function setupFetch(formResponse: unknown = FORM_WITHOUT_SELF_SELECT, options: { failFirstStart?: boolean } = {}) {
  let startAttempts = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/forms/leave')) {
        return jsonResponse(formResponse);
      }
      if (url.includes('/api/mobile/instances') && init?.method === 'POST') {
        startAttempts += 1;
        if (options.failFirstStart && startAttempts === 1) {
          return jsonResponse({ code: 'TEMPORARY_ERROR', message: '提交失败' }, 503);
        }
        return jsonResponse(START_RESULT);
      }
      if (url.includes('/api/mobile/instances/9001')) {
        return jsonResponse({
          id: 9001,
          status: 'RUNNING',
          formName: '请假申请',
          canWithdraw: true,
          history: [],
        });
      }
      return jsonResponse({});
    }),
  );
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function renderSubmitFlow(initialPath = '/forms/leave') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: '/forms/:code', element: <FormFillPage /> },
      { path: '/forms/:code/self-select', element: <SelfSelectPage /> },
      { path: '/forms/:code/confirm', element: <SubmitConfirmPage /> },
      { path: '/forms/:code/success/:instanceId', element: <SubmitSuccessPage /> },
      { path: '/processes/:instanceId', element: <ProcessDetailPage /> },
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
  useSubmitFlowStore.getState().reset();
  localStorage.clear();
  vi.unstubAllGlobals();
  setupFetch();
});

describe('mobile form submit flow', () => {
  it('navigates from form directly to confirmation when no self-select nodes exist', async () => {
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByRole('heading', { name: '提交确认' })).toBeInTheDocument();
    expect(screen.getByText('回家探亲')).toBeInTheDocument();
  });

  it('requires self-select assignees before confirmation when schema has self-select nodes', async () => {
    setupFetch(FORM_WITH_SELF_SELECT);
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByRole('heading', { name: '选择审批人' })).toBeInTheDocument();
    expect(screen.getByText('直属主管')).toBeInTheDocument();
    expect(screen.getByText('单选')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '确认选择' }));
    expect(await screen.findByText('请选择直属主管')).toBeInTheDocument();

    await userEvent.click(screen.getByText('张经理'));
    await userEvent.click(screen.getByRole('button', { name: '确认选择' }));

    expect(await screen.findByRole('heading', { name: '提交确认' })).toBeInTheDocument();
    expect(screen.getByText('张经理')).toBeInTheDocument();
  });

  it('submits with a stable idempotency key on retry, then clears state and recovery on success', async () => {
    const randomUUID = vi.fn()
      .mockReturnValueOnce('same-payload-key')
      .mockReturnValueOnce('new-payload-key');
    vi.stubGlobal('crypto', { randomUUID });
    setupFetch(FORM_WITHOUT_SELF_SELECT, { failFirstStart: true });
    localStorage.setItem(buildRecoveryKey(AUTH_USER.id, 'leave', null), '{"saved":true}');
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));
    await userEvent.click(await screen.findByRole('button', { name: '提交' }));
    await userEvent.click(await screen.findByRole('button', { name: '重试提交' }));

    await waitFor(() => {
      const calls = instancePostCalls();
      expect(calls).toHaveLength(2);
      const firstCall = calls[0];
      const secondCall = calls[1];
      expect(firstCall).toBeDefined();
      expect(secondCall).toBeDefined();
      expect(headerValue(firstCall?.[1] as RequestInit, 'Idempotency-Key')).toBe('same-payload-key');
      expect(headerValue(secondCall?.[1] as RequestInit, 'Idempotency-Key')).toBe('same-payload-key');
    });

    expect(await screen.findByRole('heading', { name: '提交成功' })).toBeInTheDocument();
    expect(localStorage.getItem(buildRecoveryKey(AUTH_USER.id, 'leave', null))).toBeNull();
    expect(useSubmitFlowStore.getState().formCode).toBeNull();
    expect(screen.getByRole('link', { name: '查看详情' })).toHaveAttribute(
      'href',
      '/processes/9001',
    );
    await userEvent.click(screen.getByRole('link', { name: '查看详情' }));
    expect(await screen.findByRole('heading', { name: '流程详情' })).toBeInTheDocument();
    expect(screen.getByText('请假申请')).toBeInTheDocument();
  });

  it('submits uploaded files through the mobile start files contract', async () => {
    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: 101,
      values: {
        reason: '回家探亲',
        attachments: [
          {
            id: '5e152409-83c4-4dbb-9fef-5782416d7bb8',
            url: '/api/mobile/files/5e152409-83c4-4dbb-9fef-5782416d7bb8/content',
            contentType: 'application/pdf',
            sizeBytes: 12,
          },
        ],
      },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');

    await userEvent.click(await screen.findByRole('button', { name: '提交' }));

    await waitFor(() => {
      const postCall = instancePostCalls()[0];
      expect(postCall).toBeDefined();
      expect(JSON.parse(String((postCall?.[1] as RequestInit | undefined)?.body))).toMatchObject({
        formCode: 'leave',
        draftId: 101,
        files: [
          {
            fileId: '5e152409-83c4-4dbb-9fef-5782416d7bb8',
            fieldId: 'attachments',
            sortOrder: 0,
          },
        ],
      });
    });
  });

  it('reuses the same idempotency key after remounting confirmation for a failed same-payload retry', async () => {
    const randomUUID = vi.fn().mockReturnValueOnce('persisted-key').mockReturnValueOnce('wrong-new-key');
    vi.stubGlobal('crypto', { randomUUID });
    setupFetch(FORM_WITHOUT_SELF_SELECT, { failFirstStart: true });
    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });

    const firstRender = renderSubmitFlow('/forms/leave/confirm');
    await userEvent.click(await screen.findByRole('button', { name: '提交' }));
    firstRender.unmount();

    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');
    await userEvent.click(await screen.findByRole('button', { name: '提交' }));

    await waitFor(() => {
      const calls = instancePostCalls();
      expect(calls).toHaveLength(2);
      expect(headerValue(calls[0]?.[1] as RequestInit, 'Idempotency-Key')).toBe('persisted-key');
      expect(headerValue(calls[1]?.[1] as RequestInit, 'Idempotency-Key')).toBe('persisted-key');
    });
  });

  it('generates a new idempotency key after a completed submission even when the next payload matches', async () => {
    const randomUUID = vi.fn().mockReturnValueOnce('completed-key').mockReturnValueOnce('new-key');
    vi.stubGlobal('crypto', { randomUUID });
    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });

    const firstRender = renderSubmitFlow('/forms/leave/confirm');
    await userEvent.click(await screen.findByRole('button', { name: '提交' }));
    await screen.findByRole('heading', { name: '提交成功' });
    firstRender.unmount();

    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');
    await userEvent.click(await screen.findByRole('button', { name: '提交' }));

    await waitFor(() => {
      const calls = instancePostCalls();
      expect(calls).toHaveLength(2);
      expect(headerValue(calls[0]?.[1] as RequestInit, 'Idempotency-Key')).toBe('completed-key');
      expect(headerValue(calls[1]?.[1] as RequestInit, 'Idempotency-Key')).toBe('new-key');
    });
  });
});

function instancePostCalls() {
  return (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls
    .filter(([url, init]) =>
      String(url).includes('/api/mobile/instances') && (init as RequestInit).method === 'POST');
}

function headerValue(init: RequestInit, name: string) {
  return new Headers(init.headers).get(name);
}
