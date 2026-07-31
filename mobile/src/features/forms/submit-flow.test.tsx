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
import { collectMobileFileRefs } from './start.api';

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
  settings: { workflowEnabled: true },
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
  settings: { workflowEnabled: true },
  schema: [
    {
      id: 'reason-group',
      type: 'span_layout',
      label: '请假事由',
      children: [
        { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
      ],
    },
    {
      id: 'remark-group',
      type: 'span_layout',
      label: '补充说明',
      children: [
        { id: 'remark', type: 'text', label: '补充说明', props: { required: true } },
      ],
    },
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

const FORM_WITH_DIRECT_SUBMIT = {
  ...FORM_WITHOUT_SELF_SELECT,
  settings: { workflowEnabled: false },
};

const START_RESULT = {
  instanceId: 9001,
  formDataId: 5001,
  businessNo: '000000005001',
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
          businessNo: '000000005001',
          applicantName: '张三',
          applicantEmployeeNo: '000007',
          applicantDepartment: '研发部',
          startedAt: '2026-07-21T09:00:00+08:00',
          currentNodeName: '直属主管',
          schema: FORM_WITHOUT_SELF_SELECT.schema,
          formData: { reason: '回家探亲' },
          processSnapshot: FORM_WITHOUT_SELF_SELECT.process,
          canWithdraw: true,
          history: [],
          files: [],
          approvalSummary: { flowedCount: 2, completedCount: 1, processingCount: 1, complete: false },
          approvalRecords: [
            { id: 'submission', taskId: null, nodeId: 'root', nodeName: '提交申请', status: 'SUBMITTED', operatorName: '张三', employeeNo: '000007', department: '研发部', comment: null, receivedAt: '2026-07-21T09:00:00+08:00', completedAt: '2026-07-21T09:00:00+08:00' },
            { id: 'task-3001', taskId: 3001, nodeId: 'manager', nodeName: '直属主管', status: 'PROCESSING', operatorName: '李经理', employeeNo: '000008', department: '研发部', comment: null, receivedAt: '2026-07-21T09:01:00+08:00', completedAt: null },
          ],
        });
      }
      if (url.includes('/api/forms/data') && init?.method === 'POST') {
        return jsonResponse({ dataId: 6001 });
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

    expect(await screen.findByRole('heading', { name: '请确认本次申请' })).toBeInTheDocument();
    expect(screen.getByText('表单编号将在提交后生成')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回编辑' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认提交' })).toHaveClass('btn', 'btn--success');
    expect(screen.getByText('回家探亲')).toBeInTheDocument();
  });

  it('shows that rework submission keeps the original business number', async () => {
    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      reworkTaskId: 401,
      values: { reason: '修改后的请假事由' },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');

    expect(await screen.findByText('本次提交将保留原单号')).toBeInTheDocument();
    expect(screen.queryByText('表单编号将在提交后生成')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认重提' })).toBeInTheDocument();
  });

  it('requires self-select assignees before confirmation when schema has self-select nodes', async () => {
    setupFetch(FORM_WITH_SELF_SELECT);
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));
    await userEvent.type(await screen.findByLabelText('补充说明'), '请尽快审批');
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByText('选择审批人')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '搜索审批人' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '完成' })).toHaveClass('app-bar__action');
    expect(screen.getByText('直属主管')).toBeInTheDocument();
    expect(screen.getByText((text) => text.includes('直属主管') && text.includes('单选'))).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '完成' }));
    expect(await screen.findByText('请选择直属主管')).toBeInTheDocument();

    await userEvent.click(screen.getByText('张经理'));
    await userEvent.click(screen.getByRole('button', { name: '完成' }));

    expect(await screen.findByRole('heading', { name: '请确认本次申请' })).toBeInTheDocument();
    expect(screen.getByText((text) => text.includes('张经理'))).toBeInTheDocument();
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
    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));
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
    expect(screen.getByRole('button', { name: '查看流程' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '查看流程' }));
    expect(await screen.findByRole('heading', { name: '请假申请' })).toBeInTheDocument();
    expect(screen.getAllByText('审批中').length).toBeGreaterThan(0);
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
            name: 'proof.pdf',
            contentUrl: '/api/mobile/files/5e152409-83c4-4dbb-9fef-5782416d7bb8/content',
            contentType: 'application/pdf',
            size: 12,
          },
        ],
      },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');

    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));

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

  it('collects file refs from current and legacy mobile file values', () => {
    expect(collectMobileFileRefs({
      attachments: [
        {
          id: '5e152409-83c4-4dbb-9fef-5782416d7bb8',
          name: 'proof.pdf',
          contentUrl: '/api/mobile/files/5e152409-83c4-4dbb-9fef-5782416d7bb8/content',
          contentType: 'application/pdf',
          size: 12,
        },
        {
          id: '85c25190-545e-4f46-a441-26e7fd0a0239',
          url: '/api/mobile/files/85c25190-545e-4f46-a441-26e7fd0a0239/content',
          contentType: 'image/png',
          sizeBytes: 9,
        },
      ],
    })).toEqual([
      {
        fileId: '5e152409-83c4-4dbb-9fef-5782416d7bb8',
        fieldId: 'attachments',
        sortOrder: 0,
      },
      {
        fileId: '85c25190-545e-4f46-a441-26e7fd0a0239',
        fieldId: 'attachments',
        sortOrder: 1,
      },
    ]);
  });

  it('submits directly when workflow is disabled', async () => {
    setupFetch(FORM_WITH_DIRECT_SUBMIT);
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(await screen.findByRole('heading', { name: '请确认本次申请' })).toBeInTheDocument();
    expect(screen.queryByText(/审批流（/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '确认提交' }));

    await waitFor(() => {
      const fetchMock = fetch as unknown as { mock: { calls: unknown[][] } };
      const directCalls = fetchMock.mock.calls.filter(([url, init]) =>
        String(url).includes('/api/forms/data') && (init as RequestInit).method === 'POST');
      expect(directCalls).toHaveLength(1);
      expect(instancePostCalls()).toHaveLength(0);
    });
    expect(await screen.findByText('表单数据已提交完成。')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看流程' })).not.toBeInTheDocument();
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
    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));
    firstRender.unmount();

    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');
    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));

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
    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));
    await screen.findByRole('heading', { name: '提交成功' });
    firstRender.unmount();

    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });
    renderSubmitFlow('/forms/leave/confirm');
    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));

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
