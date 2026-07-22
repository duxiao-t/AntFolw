import type { Page, Route } from '@playwright/test';

export type MobileUser = {
  id: number;
  username: string;
  displayName: string;
  roles: string[];
};

export const USERS = {
  bob: {
    id: 7,
    username: 'bob',
    displayName: 'Bob',
    roles: ['user'],
  } satisfies MobileUser,
  admin: {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    roles: ['admin'],
  } satisfies MobileUser,
  alice: {
    id: 9,
    username: 'alice',
    displayName: 'Alice',
    roles: ['user'],
  } satisfies MobileUser,
} as const;

export const BRANDING = {
  version: 'e2e-approval-1',
  appName: 'AntFlow 审批',
  companyName: '安',
  primaryColor: '#1677ff',
  mobileHeaderTitle: '工作台',
  loginTitle: '登录 AntFlow',
  showLoginFooter: true,
  footerText: 'AntFlow',
};

export function createRunId(prefix = 'leave'): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export function createLeaveForm(code: string) {
  return {
    code,
    name: '请假申请',
    version: 3,
    schema: [
      { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
      { id: 'days', type: 'number', label: '请假天数' },
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
            { id: USERS.admin.id, name: USERS.admin.displayName },
            { id: 22, name: '李经理' },
          ],
        },
      },
    },
  };
}

export type MockWorldOptions = {
  formCode?: string;
  seedPendingTask?: boolean;
  seedStartedProcess?: boolean;
  seedDrafts?: boolean;
  forbiddenInstanceId?: number;
};

type TaskRecord = {
  id: number;
  instanceId: number;
  formName: string;
  applicantName: string;
  applicantDepartment: string;
  nodeName: string;
  taskStatus: string;
  instanceStatus: string;
  createdAt: string;
  formCode: string;
  formData: Record<string, unknown>;
  allowedActions: string[];
  ownerUserId: number;
  history: Array<Record<string, unknown>>;
};

type InstanceRecord = {
  id: number;
  status: string;
  formName: string;
  formCode: string;
  schema: unknown[];
  formData: Record<string, unknown>;
  processSnapshot: unknown;
  history: Array<Record<string, unknown>>;
  canWithdraw: boolean;
  files: unknown[];
  starterUserId: number;
  currentNodeName?: string;
  startedAt: string;
  finishedAt?: string | null;
  firstTaskId?: number;
};

export type MockWorld = {
  formCode: string;
  form: ReturnType<typeof createLeaveForm>;
  sessionUser: MobileUser | null;
  instances: Map<number, InstanceRecord>;
  tasks: Map<number, TaskRecord>;
  startKeys: Map<string, number>;
  actionKeys: Map<string, string>;
  drafts: Array<Record<string, unknown>>;
  nextInstanceId: number;
  nextTaskId: number;
  nextDraftId: number;
  startPostCount: number;
  approvePostCount: number;
};

function tokenFor(user: MobileUser) {
  return `e2e-token-${user.username}`;
}

function userFromToken(authHeader: string | null): MobileUser | null {
  if (!authHeader?.startsWith('Bearer ')) {
    return null;
  }
  const token = authHeader.slice('Bearer '.length);
  const entry = Object.values(USERS).find((user) => tokenFor(user) === token);
  return entry ?? null;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

function empty(route: Route, status = 204) {
  return route.fulfill({ status, body: '' });
}

function error(route: Route, status: number, code: string, message: string) {
  return json(route, { code, message, traceId: `e2e-${status}` }, status);
}

export function createMockWorld(options: MockWorldOptions = {}): MockWorld {
  const formCode = options.formCode ?? createRunId('leave');
  const form = createLeaveForm(formCode);
  const world: MockWorld = {
    formCode,
    form,
    sessionUser: null,
    instances: new Map(),
    tasks: new Map(),
    startKeys: new Map(),
    actionKeys: new Map(),
    drafts: [],
    nextInstanceId: 9001,
    nextTaskId: 401,
    nextDraftId: 101,
    startPostCount: 0,
    approvePostCount: 0,
  };

  if (options.seedPendingTask) {
    seedTask(world, {
      ownerUserId: USERS.admin.id,
      starterUserId: USERS.bob.id,
      applicantName: USERS.bob.displayName,
      status: 'PENDING',
      instanceStatus: 'RUNNING',
      formData: { reason: '回家探亲', days: 2 },
    });
  }

  if (options.seedStartedProcess) {
    seedInstance(world, {
      starterUserId: USERS.bob.id,
      status: 'RUNNING',
      formData: { reason: '回家探亲', days: 2 },
      canWithdraw: true,
    });
  }

  if (options.seedDrafts) {
    world.drafts.push({
      id: world.nextDraftId++,
      formCode,
      formName: form.name,
      formVersion: form.version,
      updatedAt: '2026-07-20T10:00:00+08:00',
      data: { reason: '草稿内容' },
      schema: form.schema,
      readOnly: false,
    });
  }

  if (options.forbiddenInstanceId) {
    world.instances.set(options.forbiddenInstanceId, {
      id: options.forbiddenInstanceId,
      status: 'RUNNING',
      formName: form.name,
      formCode,
      schema: form.schema,
      formData: { reason: '机密流程' },
      processSnapshot: form.process,
      history: [],
      canWithdraw: false,
      files: [],
      starterUserId: USERS.admin.id,
      currentNodeName: '直属主管',
      startedAt: '2026-07-20T09:00:00+08:00',
    });
  }

  return world;
}

function seedInstance(
  world: MockWorld,
  partial: {
    starterUserId: number;
    status: string;
    formData: Record<string, unknown>;
    canWithdraw?: boolean;
  },
) {
  const id = world.nextInstanceId++;
  const record: InstanceRecord = {
    id,
    status: partial.status,
    formName: world.form.name,
    formCode: world.formCode,
    schema: world.form.schema,
    formData: partial.formData,
    processSnapshot: world.form.process,
    history: [
      {
        id: 1,
        fromNodeId: 'root',
        toNodeId: 'manager',
        action: 'ARRIVE',
        operatorId: partial.starterUserId,
        comment: null,
        createdAt: '2026-07-20T09:00:00+08:00',
      },
    ],
    canWithdraw: partial.canWithdraw ?? partial.status === 'RUNNING',
    files: [],
    starterUserId: partial.starterUserId,
    currentNodeName: partial.status === 'RUNNING' ? '直属主管' : undefined,
    startedAt: '2026-07-20T09:00:00+08:00',
    finishedAt: partial.status === 'RUNNING' ? null : '2026-07-20T11:00:00+08:00',
  };
  world.instances.set(id, record);
  return record;
}

function seedTask(
  world: MockWorld,
  partial: {
    ownerUserId: number;
    starterUserId: number;
    applicantName: string;
    status: string;
    instanceStatus: string;
    formData: Record<string, unknown>;
  },
) {
  const instance = seedInstance(world, {
    starterUserId: partial.starterUserId,
    status: partial.instanceStatus,
    formData: partial.formData,
    canWithdraw: partial.instanceStatus === 'RUNNING',
  });
  const taskId = world.nextTaskId++;
  const task: TaskRecord = {
    id: taskId,
    instanceId: instance.id,
    formName: world.form.name,
    applicantName: partial.applicantName,
    applicantDepartment: '研发部',
    nodeName: '直属主管',
    taskStatus: partial.status,
    instanceStatus: partial.instanceStatus,
    createdAt: '2026-07-20T09:05:00+08:00',
    formCode: world.formCode,
    formData: partial.formData,
    allowedActions: partial.status === 'PENDING' ? ['APPROVE', 'REJECT'] : [],
    ownerUserId: partial.ownerUserId,
    history: instance.history,
  };
  instance.firstTaskId = taskId;
  world.tasks.set(taskId, task);
  return task;
}

function bootstrapFor(user: MobileUser, world: MockWorld) {
  const pendingCount = [...world.tasks.values()].filter(
    (task) => task.ownerUserId === user.id && task.taskStatus === 'PENDING',
  ).length;
  const recentProcesses = [...world.instances.values()]
    .filter((item) => item.starterUserId === user.id)
    .slice(0, 5)
    .map((item) => ({
      instanceId: item.id,
      formCode: item.formCode,
      formTitle: item.formName,
      status: item.status,
      updatedAt: item.startedAt,
    }));

  return {
    user,
    pendingCount,
    favoriteApps: [
      {
        formId: 1,
        code: world.formCode,
        name: world.form.name,
        category: 'hr',
        categoryLabel: '人事',
      },
      {
        formId: 2,
        code: 'expense',
        name: '费用报销',
        category: 'finance',
        categoryLabel: '财务',
      },
    ],
    recentProcesses,
    brandingVersion: BRANDING.version,
  };
}

function appsFor(world: MockWorld) {
  return [
    {
      formId: 1,
      code: world.formCode,
      name: world.form.name,
      category: 'hr',
      categoryLabel: '人事',
    },
    {
      formId: 2,
      code: 'expense',
      name: '费用报销',
      category: 'finance',
      categoryLabel: '财务',
    },
    {
      formId: 3,
      code: 'travel',
      name: '出差审批',
      category: 'admin',
      categoryLabel: '行政',
    },
  ];
}

function taskDetail(task: TaskRecord, world: MockWorld) {
  return {
    task: {
      id: task.id,
      instanceId: task.instanceId,
      formName: task.formName,
      applicantName: task.applicantName,
      applicantDepartment: task.applicantDepartment,
      nodeName: task.nodeName,
      taskStatus: task.taskStatus,
      instanceStatus: task.instanceStatus,
      createdAt: task.createdAt,
    },
    schema: world.form.schema,
    formData: task.formData,
    processSnapshot: world.form.process,
    history: task.history,
    allowedActions: task.allowedActions,
    rejectTargets: [{ nodeId: 'root', name: '发起人' }],
    files: [
      {
        id: 'd2cecb38-11a8-4d2e-9f43-96ce6f4a7e60',
        name: '证明.pdf',
        contentType: 'application/pdf',
        size: 1024,
        contentUrl: '/api/mobile/files/d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60/content',
      },
    ],
  };
}

function instanceDetail(instance: InstanceRecord) {
  return {
    id: instance.id,
    status: instance.status,
    formName: instance.formName,
    schema: instance.schema,
    formData: instance.formData,
    processSnapshot: instance.processSnapshot,
    history: instance.history,
    canWithdraw: instance.canWithdraw,
    files: instance.files,
  };
}

export async function installApiMocks(page: Page, world: MockWorld) {
  // Only intercept backend /api paths. Glob **/api/** also matches Vite modules under /src/shared/api/.
  await page.route(
    (url) => {
      const path = url.pathname;
      return path === '/api' || path.startsWith('/api/');
    },
    async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method().toUpperCase();
    const authUser = userFromToken(request.headers().authorization ?? null) ?? world.sessionUser;

    try {
      if (path === '/api/public/branding' && method === 'GET') {
        return json(route, BRANDING);
      }

      if (path === '/api/auth/login' && method === 'POST') {
        const body = request.postDataJSON() as { username?: string; password?: string };
        const user = Object.values(USERS).find((entry) => entry.username === body.username);
        if (!user || body.password !== 'ant.design') {
          return error(route, 401, 'BAD_CREDENTIALS', '账号或密码错误');
        }
        world.sessionUser = user;
        return json(route, { accessToken: tokenFor(user), user });
      }

      if (path === '/api/auth/refresh' && method === 'POST') {
        if (!world.sessionUser) {
          return error(route, 401, 'TOKEN_EXPIRED', '会话已过期');
        }
        return json(route, {
          accessToken: tokenFor(world.sessionUser),
          user: world.sessionUser,
        });
      }

      if (path === '/api/auth/logout' && method === 'POST') {
        world.sessionUser = null;
        return empty(route, 204);
      }

      if (path === '/api/auth/sessions' && method === 'GET') {
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        return json(route, [
          {
            id: `session-${authUser.username}`,
            deviceName: 'E2E Browser',
            platform: 'browser',
            lastActiveAt: '2026-07-20 12:00',
            isCurrent: true,
          },
        ]);
      }

      if (path.startsWith('/api/auth/sessions/') && method === 'DELETE') {
        return empty(route, 204);
      }

      if (path === '/api/mobile/bootstrap' && method === 'GET') {
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        return json(route, bootstrapFor(authUser, world));
      }

      if (path === '/api/mobile/apps' && method === 'GET') {
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        return json(route, appsFor(world));
      }

      if (path === '/api/mobile/apps/favorites' && method === 'GET') {
        return json(route, bootstrapFor(authUser ?? USERS.bob, world).favoriteApps);
      }

      if (path === '/api/mobile/preferences/apps' && method === 'PUT') {
        return empty(route, 204);
      }

      if (path.startsWith('/api/mobile/forms/') && method === 'GET') {
        const code = decodeURIComponent(path.slice('/api/mobile/forms/'.length));
        if (code === world.formCode || code === 'leave') {
          const payload = code === world.formCode ? world.form : createLeaveForm(code);
          return json(route, payload);
        }
        return error(route, 404, 'FORM_NOT_FOUND', '表单不存在');
      }

      if (path === '/api/mobile/drafts' && method === 'GET') {
        return json(route, world.drafts);
      }

      if (path === '/api/mobile/drafts' && method === 'POST') {
        const body = request.postDataJSON() as Record<string, unknown>;
        const draft = {
          id: world.nextDraftId++,
          formCode: String(body.formCode ?? world.formCode),
          formName: world.form.name,
          formVersion: world.form.version,
          updatedAt: '2026-07-20T12:00:00+08:00',
          data: (body.data as Record<string, unknown>) ?? (body.values as Record<string, unknown>) ?? {},
          schema: world.form.schema,
          readOnly: false,
        };
        world.drafts.unshift(draft);
        return json(route, draft.id);
      }

      if (path.startsWith('/api/mobile/drafts/') && method === 'GET') {
        const id = Number(path.split('/').pop());
        const draft = world.drafts.find((item) => item.id === id);
        if (!draft) {
          return error(route, 404, 'DRAFT_NOT_FOUND', '草稿不存在');
        }
        return json(route, draft);
      }

      if (path.startsWith('/api/mobile/drafts/') && method === 'PUT') {
        const id = Number(path.split('/').pop());
        const body = request.postDataJSON() as Record<string, unknown>;
        const draft = world.drafts.find((item) => item.id === id);
        if (!draft) {
          return error(route, 404, 'DRAFT_NOT_FOUND', '草稿不存在');
        }
        draft.data =
          (body.data as Record<string, unknown>) ??
          (body.values as Record<string, unknown>) ??
          draft.data;
        draft.updatedAt = '2026-07-20T12:00:00+08:00';
        return json(route, draft);
      }

      if (path.startsWith('/api/mobile/drafts/') && method === 'DELETE') {
        const id = Number(path.split('/').pop());
        world.drafts = world.drafts.filter((item) => item.id !== id);
        return empty(route, 204);
      }

      if (path === '/api/mobile/instances' && method === 'POST') {
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        world.startPostCount += 1;
        const idempotencyKey = request.headers()['idempotency-key'] ?? request.headers()['Idempotency-Key'];
        if (idempotencyKey && world.startKeys.has(idempotencyKey)) {
          const existingId = world.startKeys.get(idempotencyKey)!;
          const existing = world.instances.get(existingId)!;
          return json(route, {
            instanceId: existing.id,
            formDataId: existing.id + 1000,
            firstTaskIds: existing.firstTaskId ? [existing.firstTaskId] : [],
          });
        }

        const body = request.postDataJSON() as {
          formCode?: string;
          data?: Record<string, unknown>;
          values?: Record<string, unknown>;
          selfSelected?: Record<string, number[]>;
        };
        const formData = body.data ?? body.values ?? {};
        const instance = seedInstance(world, {
          starterUserId: authUser.id,
          status: 'RUNNING',
          formData,
          canWithdraw: true,
        });
        const assigneeIds = body.selfSelected?.manager ?? [USERS.admin.id];
        const ownerUserId = assigneeIds[0] ?? USERS.admin.id;
        const taskId = world.nextTaskId++;
        const task: TaskRecord = {
          id: taskId,
          instanceId: instance.id,
          formName: world.form.name,
          applicantName: authUser.displayName,
          applicantDepartment: '研发部',
          nodeName: '直属主管',
          taskStatus: 'PENDING',
          instanceStatus: 'RUNNING',
          createdAt: '2026-07-20T12:00:00+08:00',
          formCode: world.formCode,
          formData,
          allowedActions: ['APPROVE', 'REJECT'],
          ownerUserId,
          history: instance.history,
        };
        instance.firstTaskId = taskId;
        world.tasks.set(taskId, task);
        if (idempotencyKey) {
          world.startKeys.set(idempotencyKey, instance.id);
        }
        return json(route, {
          instanceId: instance.id,
          formDataId: instance.id + 1000,
          firstTaskIds: [taskId],
        });
      }

      if (path === '/api/mobile/instances' && method === 'GET') {
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        const items = [...world.instances.values()]
          .filter((item) => item.starterUserId === authUser.id)
          .map((item) => ({
            id: item.id,
            status: item.status,
            formName: item.formName,
            currentNodeName: item.currentNodeName,
            startedAt: item.startedAt,
            finishedAt: item.finishedAt ?? null,
          }));
        return json(route, { items, hasMore: false });
      }

      if (path.match(/^\/api\/mobile\/instances\/\d+$/) && method === 'GET') {
        const id = Number(path.split('/').pop());
        const instance = world.instances.get(id);
        if (!instance) {
          return error(route, 404, 'INSTANCE_NOT_FOUND', '流程不存在');
        }
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        const relatedTask = [...world.tasks.values()].some(
          (task) => task.instanceId === id && task.ownerUserId === authUser.id,
        );
        if (instance.starterUserId !== authUser.id && !relatedTask && authUser.username === 'alice') {
          return error(route, 403, 'FORBIDDEN', '无权查看该流程');
        }
        return json(route, instanceDetail(instance));
      }

      if (path.match(/^\/api\/mobile\/instances\/\d+\/withdraw$/) && method === 'POST') {
        const id = Number(path.split('/')[4]);
        const instance = world.instances.get(id);
        if (!instance) {
          return error(route, 404, 'INSTANCE_NOT_FOUND', '流程不存在');
        }
        if (!instance.canWithdraw) {
          return error(route, 409, 'ALREADY_ACTED', '流程已处理');
        }
        instance.status = 'WITHDRAWN';
        instance.canWithdraw = false;
        instance.currentNodeName = undefined;
        instance.finishedAt = '2026-07-20T12:30:00+08:00';
        for (const task of world.tasks.values()) {
          if (task.instanceId === id) {
            task.taskStatus = 'SKIPPED';
            task.instanceStatus = 'WITHDRAWN';
            task.allowedActions = [];
          }
        }
        return empty(route, 204);
      }

      if (path === '/api/mobile/tasks' && method === 'GET') {
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        const view = url.searchParams.get('view') ?? 'pending';
        const items = [...world.tasks.values()]
          .filter((task) => {
            if (task.ownerUserId !== authUser.id) {
              return false;
            }
            if (view === 'done') {
              return task.taskStatus !== 'PENDING';
            }
            return task.taskStatus === 'PENDING';
          })
          .map((task) => ({
            id: task.id,
            instanceId: task.instanceId,
            formName: task.formName,
            applicantName: task.applicantName,
            applicantDepartment: task.applicantDepartment,
            nodeName: task.nodeName,
            taskStatus: task.taskStatus,
            instanceStatus: task.instanceStatus,
            createdAt: task.createdAt,
          }));
        return json(route, { items, hasMore: false });
      }

      if (path.match(/^\/api\/mobile\/tasks\/\d+$/) && method === 'GET') {
        const id = Number(path.split('/').pop());
        const task = world.tasks.get(id);
        if (!task) {
          return error(route, 404, 'TASK_NOT_FOUND', '任务不存在');
        }
        if (!authUser) {
          return error(route, 401, 'UNAUTHORIZED', '未登录');
        }
        if (task.ownerUserId !== authUser.id && authUser.username === 'alice') {
          return error(route, 403, 'FORBIDDEN', '无权处理该任务');
        }
        return json(route, taskDetail(task, world));
      }

      if (path.match(/^\/api\/mobile\/tasks\/\d+\/approve$/) && method === 'POST') {
        const id = Number(path.split('/')[4]);
        const task = world.tasks.get(id);
        if (!task) {
          return error(route, 404, 'TASK_NOT_FOUND', '任务不存在');
        }
        world.approvePostCount += 1;
        const idempotencyKey = request.headers()['idempotency-key'] ?? request.headers()['Idempotency-Key'];
        if (idempotencyKey && world.actionKeys.has(`approve:${id}:${idempotencyKey}`)) {
          return empty(route, 204);
        }
        if (task.taskStatus !== 'PENDING') {
          return error(route, 409, 'ALREADY_ACTED', '任务已被处理');
        }
        task.taskStatus = 'APPROVED';
        task.instanceStatus = 'APPROVED';
        task.allowedActions = [];
        task.history = [
          ...task.history,
          {
            id: task.history.length + 1,
            fromNodeId: 'manager',
            toNodeId: null,
            action: 'APPROVE',
            operatorId: authUser?.id ?? USERS.admin.id,
            comment: (request.postDataJSON() as { comment?: string } | null)?.comment ?? null,
            createdAt: '2026-07-20T12:10:00+08:00',
          },
        ];
        const instance = world.instances.get(task.instanceId);
        if (instance) {
          instance.status = 'APPROVED';
          instance.canWithdraw = false;
          instance.currentNodeName = undefined;
          instance.finishedAt = '2026-07-20T12:10:00+08:00';
          instance.history = task.history;
        }
        if (idempotencyKey) {
          world.actionKeys.set(`approve:${id}:${idempotencyKey}`, 'done');
        }
        return empty(route, 204);
      }

      if (path.match(/^\/api\/mobile\/tasks\/\d+\/reject$/) && method === 'POST') {
        const id = Number(path.split('/')[4]);
        const task = world.tasks.get(id);
        if (!task) {
          return error(route, 404, 'TASK_NOT_FOUND', '任务不存在');
        }
        const body = (request.postDataJSON() as { comment?: string; rejectToNodeId?: string }) ?? {};
        if (!body.comment) {
          return error(route, 400, 'VALIDATION', '请填写驳回原因');
        }
        task.taskStatus = 'REJECTED';
        task.instanceStatus = 'REJECTED';
        task.allowedActions = [];
        const instance = world.instances.get(task.instanceId);
        if (instance) {
          instance.status = 'REJECTED';
          instance.canWithdraw = false;
          instance.currentNodeName = undefined;
          instance.finishedAt = '2026-07-20T12:15:00+08:00';
        }
        return empty(route, 204);
      }

      // favicon / manifest / unknown public assets
      if (path.startsWith('/api/public/')) {
        return route.fulfill({ status: 200, body: '' });
      }

      return json(route, {});
    } catch (err) {
      return error(route, 500, 'MOCK_ERROR', err instanceof Error ? err.message : 'mock failure');
    }
  });
}

