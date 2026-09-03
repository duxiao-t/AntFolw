import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../auth/auth.store';
import type { MobileUser } from '../../shared/api/types';
import { queryKeys } from '../../shared/api/queryKeys';
import { buildRecoveryKey } from '../../shared/recovery/userScopedStorage';
import { FormFillPage } from './FormFillPage';
import { SelfSelectPage } from './SelfSelectPage';
import { SubmitConfirmPage } from './SubmitConfirmPage';
import { SubmitSuccessPage } from './SubmitSuccessPage';
import { findSelfSelectRules, useSubmitFlowStore } from './submitFlow.store';
import { ProcessDetailPage } from '../processes/ProcessDetailPage';
import { TaskCenterPage } from '../tasks/TaskCenterPage';
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
      },
    },
  },
};

const FORM_WITH_MULTIPLE_SELF_SELECT = {
  ...FORM_WITH_SELF_SELECT,
  process: {
    id: 'root',
    type: 'ROOT',
    children: {
      id: 'single-approver',
      type: 'APPROVAL',
      name: '单人审批',
      props: {
        assignedType: 'SELF_SELECT',
        mode: 'OR',
        selfSelect: { multiple: false },
      },
      children: {
        id: 'multiple-approvers',
        type: 'APPROVAL',
        name: '多人审批',
        props: {
          assignedType: 'SELF_SELECT',
          mode: 'AND',
          selfSelect: { multiple: true },
        },
      },
    },
  },
};

const SELF_SELECT_USERS = [
  { id: 21, displayName: '张经理', username: 'zhang', department: '研发部', employeeNo: '000021' },
  { id: 22, displayName: '李经理', username: 'li', department: '制造部', employeeNo: '000022' },
  { id: 23, displayName: '王经理', username: 'wang', department: '质量部', employeeNo: '000023' },
  { id: 24, displayName: '超长工号员工', username: 'long-no', department: '制造部', employeeNo: 'WECOM-0000000000000000000000000001' },
  ...Array.from({ length: 9 }, (_, index) => ({
    id: 30 + index,
    displayName: `候选员工${index + 1}`,
    username: `candidate-${index + 1}`,
    department: '生产部',
    employeeNo: `0000${30 + index}`,
  })),
];

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
      if (url.includes('/api/mobile/users')) {
        const keyword = new URL(url, 'http://localhost').searchParams.get('keyword') ?? '';
        return jsonResponse(SELF_SELECT_USERS.filter((user) =>
          `${user.displayName} ${user.username} ${user.department} ${user.employeeNo}`.includes(keyword)));
      }
      if (url.includes('/api/mobile/forms/leave')) {
        return jsonResponse(formResponse);
      }
      if (url.includes('/api/mobile/rework-tasks/401/resubmit') && init?.method === 'POST') {
        return jsonResponse(START_RESULT);
      }
      if (url.includes('/api/mobile/instances') && init?.method === 'POST') {
        startAttempts += 1;
        if (options.failFirstStart && startAttempts === 1) {
          return jsonResponse({ code: 'TEMPORARY_ERROR', message: '提交失败' }, 503);
        }
        return jsonResponse(START_RESULT);
      }
      if (url.includes('/api/mobile/drafts') && init?.method === 'POST') {
        return jsonResponse(101);
      }
      if (url.includes('/api/mobile/drafts/101')) {
        return jsonResponse({
          id: 101,
          formCode: 'leave',
          formName: '请假申请',
          formVersion: 3,
          data: { reason: '回家探亲' },
          readOnly: false,
        });
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
      if (url.includes('/api/mobile/tasks?')) {
        return jsonResponse({ items: [], hasMore: false });
      }
      if (url.includes('/api/forms/data') && init?.method === 'POST') {
        return jsonResponse({ dataId: 6001, businessNo: 'DIRECT-20260902-0001' });
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
      { path: '/tasks', element: <TaskCenterPage /> },
    ],
    { initialEntries: [initialPath] },
  );
  return {
    ...render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
    ),
    queryClient,
    router,
  };
}

beforeEach(() => {
  useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
  useSubmitFlowStore.getState().reset();
  localStorage.clear();
  vi.unstubAllGlobals();
  setupFetch();
});

describe('mobile form submit flow', () => {
  it('uses the flow node name and approval mode independently of selection cardinality', () => {
    const rules = findSelfSelectRules({
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'node_gE8H22GO',
        type: 'APPROVAL',
        name: '审批人',
        props: { assignedType: 'SELF_SELECT', mode: 'OR', selfSelect: { multiple: true } },
        children: {
          id: 'node_review',
          type: 'APPROVAL',
          name: '复核人',
          props: { assignedType: 'SELF_SELECT', mode: 'ALL', selfSelect: { multiple: false } },
        },
      },
    });

    expect(rules).toMatchObject([
      { nodeId: 'node_gE8H22GO', name: '审批人', multiple: true, approvalMode: 'OR' },
      { nodeId: 'node_review', name: '复核人', multiple: false, approvalMode: 'AND' },
    ]);
  });

  it('navigates from form directly to confirmation when no self-select nodes exist', async () => {
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.click(screen.getByRole('button', { name: '提交' }));

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

  it('clears stale workflow caches and refetches pending tasks after rework', async () => {
    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      reworkTaskId: 401,
      values: { reason: '修改后的请假事由' },
      selfSelected: {},
    });
    const { queryClient, router } = renderSubmitFlow('/forms/leave/confirm');
    const pendingKey = queryKeys.tasks({ view: 'pending', page: 1, size: 20 });
    const taskKeys = [
      pendingKey,
      queryKeys.tasks({ view: 'process', keyword: '请假', page: 2, size: 20 }),
      queryKeys.tasks({ view: 'done', status: 'APPROVED', page: 1, size: 20 }),
      queryKeys.taskDetail(401),
    ];
    taskKeys.forEach((key) => {
      queryClient.setQueryData(key, { stale: true });
    });
    queryClient.setQueryData(pendingKey, {
      items: [{
        kind: 'task',
        view: 'pending',
        task: {
          id: 401,
          instanceId: 9001,
          nodeId: '__rework__',
          formCode: 'leave',
          formName: '请假申请',
          businessNo: '000000005001',
          applicantName: '张三',
          nodeName: '待修改原单',
          taskType: 'REWORK',
          taskStatus: 'PENDING',
          instanceStatus: 'RUNNING',
          createdAt: '2026-08-29T16:52:00+08:00',
        },
      }],
      hasMore: false,
    });
    queryClient.setQueryData(queryKeys.reworkTask(401), { taskId: 401 });
    queryClient.setQueryData(queryKeys.instance(START_RESULT.instanceId), { id: START_RESULT.instanceId });
    queryClient.setQueryData(queryKeys.bootstrap, { pendingCount: 1 });

    await userEvent.click(await screen.findByRole('button', { name: '确认重提' }));
    expect(await screen.findByRole('heading', { name: '提交成功' })).toBeInTheDocument();

    taskKeys.forEach((key) => {
      expect(queryClient.getQueryData(key)).toBeUndefined();
    });
    expect(queryClient.getQueryData(queryKeys.reworkTask(401))).toBeUndefined();
    expect(queryClient.getQueryData(queryKeys.instance(START_RESULT.instanceId))).toBeUndefined();
    expect(queryClient.getQueryState(queryKeys.bootstrap)?.isInvalidated).toBe(true);

    await userEvent.click(screen.getByRole('button', { name: '查看流程' }));
    expect(await screen.findByRole('heading', { name: '请假申请' })).toBeInTheDocument();
    await router.navigate('/tasks?view=pending');
    expect(await screen.findByText('暂无待办任务')).toBeInTheDocument();
    expect(screen.queryByText('待修改原单')).not.toBeInTheDocument();
    expect((fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.some(
      ([url]) => String(url).includes('/api/mobile/tasks?'),
    )).toBe(true);
  });

  it('clears task and instance caches after a new workflow starts', async () => {
    useSubmitFlowStore.setState({
      formCode: 'leave',
      draftId: null,
      reworkTaskId: null,
      values: { reason: '回家探亲' },
      selfSelected: {},
    });
    const { queryClient } = renderSubmitFlow('/forms/leave/confirm');
    const processKey = queryKeys.tasks({ view: 'process', page: 1, size: 20 });
    queryClient.setQueryData(processKey, { items: [], hasMore: false });
    queryClient.setQueryData(queryKeys.instance(START_RESULT.instanceId), { stale: true });
    queryClient.setQueryData(queryKeys.bootstrap, { pendingCount: 0 });

    await userEvent.click(await screen.findByRole('button', { name: '确认提交' }));
    expect(await screen.findByRole('heading', { name: '提交成功' })).toBeInTheDocument();

    expect(queryClient.getQueryData(processKey)).toBeUndefined();
    expect(queryClient.getQueryData(queryKeys.instance(START_RESULT.instanceId))).toBeUndefined();
    expect(queryClient.getQueryState(queryKeys.bootstrap)?.isInvalidated).toBe(true);
  });

  it('requires self-select assignees before confirmation when schema has self-select nodes', async () => {
    setupFetch(FORM_WITH_SELF_SELECT);
    renderSubmitFlow();

    await userEvent.type(await screen.findByLabelText('请假事由'), '回家探亲');
    await userEvent.type(await screen.findByLabelText('补充说明'), '请尽快审批');
    await userEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(await screen.findByText('选择审批人')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '搜索审批人' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '完成' })).toHaveClass('app-bar__action');
    expect(screen.getByText('直属主管')).toBeInTheDocument();
    expect(screen.getByText((text) => text.includes('直属主管') && text.includes('单选'))).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '完成' }));
    expect(await screen.findByText('请选择直属主管')).toBeInTheDocument();

    await userEvent.click(await screen.findByRole('button', { name: /张经理/ }));
    await userEvent.click(screen.getByRole('button', { name: '完成' }));

    expect(await screen.findByRole('heading', { name: '请确认本次申请' })).toBeInTheDocument();
    expect(screen.getByText((text) => text.includes('张经理'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '确认提交' }));
    await waitFor(() => {
      const call = instancePostCalls()[0];
      if (!call) throw new Error('instance start call missing');
      expect(JSON.parse(String((call[1] as RequestInit).body))).toMatchObject({
        selfSelected: { manager: [21] },
      });
    });
  });

  it('limits the people grid to three rows and keeps hidden matches searchable', async () => {
    const user = userEvent.setup();
    setupFetch(FORM_WITH_MULTIPLE_SELF_SELECT);
    useSubmitFlowStore.setState({
      formCode: 'leave', draftId: null, reworkTaskId: null, values: {}, selfSelected: {}, selfSelectedUsers: {},
    });
    renderSubmitFlow('/forms/leave/self-select');

    await screen.findByRole('button', { name: /张经理/ });
    const people = () => {
      const peopleGrid = document.querySelector<HTMLElement>('.people-grid');
      if (!peopleGrid) throw new Error('people grid missing');
      return within(peopleGrid);
    };
    expect(people().getAllByRole('button')).toHaveLength(11);
    expect(people().getByRole('status', { name: '还有 2 名匹配人员，请继续搜索' })).toHaveTextContent('…');

    const search = screen.getByRole('searchbox', { name: '搜索审批人' });
    await user.type(search, '候选员工8');
    await waitFor(() => expect((fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.some(
      ([url]) => String(url).includes(`/api/mobile/users?keyword=${encodeURIComponent('候选员工8')}`),
    )).toBe(true));
    const hiddenPerson = await screen.findByRole('button', { name: /候选员工8/ });
    expect(people().queryByRole('status')).not.toBeInTheDocument();
    await user.click(hiddenPerson);
    expect(useSubmitFlowStore.getState().selfSelected).toEqual({ 'single-approver': [37] });
  });

  it('searches live employees and keeps single and multiple self-select nodes separate', async () => {
    const user = userEvent.setup();
    setupFetch(FORM_WITH_MULTIPLE_SELF_SELECT);
    useSubmitFlowStore.setState({
      formCode: 'leave', draftId: null, reworkTaskId: null, values: {}, selfSelected: {}, selfSelectedUsers: {},
    });
    renderSubmitFlow('/forms/leave/self-select');

    const search = await screen.findByRole('searchbox', { name: '搜索审批人' });
    expect(screen.getByText('单人审批 · 单选 · 或签')).toBeInTheDocument();
    await user.type(search, '李');
    await waitFor(() => expect(search).toHaveFocus());
    expect(search).toHaveValue('李');
    await waitFor(() => expect((fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.some(
      ([url]) => String(url).includes('/api/mobile/users?keyword=%E6%9D%8E'),
    )).toBe(true));

    const people = () => within(document.querySelector('.people-grid') as HTMLElement);
    expect(await people().findByText('制造部 · 工号 000022')).toBeInTheDocument();
    await user.click(people().getByRole('button', { name: /李经理/ }));
    expect(useSubmitFlowStore.getState().selfSelected).toEqual({ 'single-approver': [22] });
    expect(within(document.querySelector('.list-card') as HTMLElement).getByText('或签')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '多人审批' }));
    expect(screen.getByText('多人审批 · 可多选 · 会签')).toBeInTheDocument();
    await user.clear(search);
    await waitFor(() => expect(search).toHaveFocus());
    expect(search).toHaveValue('');
    await people().findByRole('button', { name: /张经理/ });
    const longIdentity = '制造部 · 工号 WECOM-0000000000000000000000000001';
    expect(people().getByText(longIdentity)).toHaveAttribute('title', longIdentity);
    await user.click(people().getByRole('button', { name: /张经理/ }));
    await user.click(people().getByRole('button', { name: /超长工号员工/ }));
    await user.click(people().getByRole('button', { name: /李经理/ }));
    expect(useSubmitFlowStore.getState().selfSelected).toEqual({
      'single-approver': [22],
      'multiple-approvers': [21, 24, 22],
    });
    const selectedList = document.querySelector('.list-card') as HTMLElement;
    expect(within(selectedList).getAllByRole('button')).toHaveLength(4);
    expect(within(selectedList).getAllByText('会签')).toHaveLength(3);
    expect(within(selectedList).queryByText('…')).not.toBeInTheDocument();
    expect(screen.getByText((text, element) =>
      Boolean(element?.classList.contains('self-select__identity') && text.includes('WECOM-0000000000000000000000000001')),
    )).toBeInTheDocument();

    await user.click(people().getByRole('button', { name: /李经理/ }));
    expect(useSubmitFlowStore.getState().selfSelected['multiple-approvers']).toEqual([21, 24]);
    expect(within(selectedList).getAllByRole('button')).toHaveLength(3);
    expect(useSubmitFlowStore.getState().selfSelectedUsers[21]).toMatchObject({
      name: '张经理', department: '研发部', employeeNo: '000021',
    });
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
    await userEvent.click(screen.getByRole('button', { name: '提交' }));
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
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    await screen.findByText('草稿已保存');
    await userEvent.type(await screen.findByLabelText('请假事由'), '（再次修改）');
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    await waitFor(() => expect((fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls
      .filter(([url, init]) => String(url).includes('/api/mobile/drafts/101')
        && (init as RequestInit).method === 'PUT')).toHaveLength(1));
    await userEvent.click(await screen.findByRole('button', { name: '提交' }));

    expect(await screen.findByRole('heading', { name: '请确认本次申请' })).toBeInTheDocument();
    expect(screen.queryByText(/审批流（/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '确认提交' }));

    await waitFor(() => {
      const fetchMock = fetch as unknown as { mock: { calls: unknown[][] } };
      const directCalls = fetchMock.mock.calls.filter(([url, init]) =>
        String(url).includes('/api/forms/data') && (init as RequestInit).method === 'POST');
      expect(directCalls).toHaveLength(1);
      const directCall = directCalls[0];
      if (!directCall) throw new Error('direct submit call missing');
      expect(JSON.parse(String((directCall[1] as RequestInit).body))).toMatchObject({ draftId: 101 });
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
