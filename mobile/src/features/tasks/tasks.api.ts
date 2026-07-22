import { apiRequest } from '../../shared/api/http';

export type TaskView = 'pending' | 'done' | 'process';

export type TaskListItem = {
  id: number;
  instanceId: number;
  formName: string;
  applicantName: string;
  applicantDepartment?: string;
  nodeName: string;
  taskStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED' | 'CC' | string;
  instanceStatus: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string;
  createdAt: string;
};

export type StartedProcessItem = {
  id: number;
  status: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string;
  formName: string;
  currentNodeName?: string;
  startedAt: string;
  finishedAt?: string | null;
};

export type TaskCenterFilters = {
  view: TaskView;
  keyword?: string;
  status?: string;
  page?: number;
  size?: number;
};

export type TaskPageResult<T> = {
  items: T[];
  hasMore: boolean;
};

export type TaskCenterItem =
  | { kind: 'task'; view: 'pending' | 'done'; task: TaskListItem }
  | { kind: 'process'; process: StartedProcessItem };

type PagedResponse<T> = {
  items: T[];
  hasMore: boolean;
};

const DEFAULT_PAGE = 1;
const DEFAULT_SIZE = 20;

export async function fetchTaskCenterItems(filters: TaskCenterFilters): Promise<TaskPageResult<TaskCenterItem>> {
  if (filters.view === 'process') {
    const result = await fetchList<StartedProcessItem>('/api/mobile/instances', filters);
    return {
      items: result.items.map((process) => ({ kind: 'process', process })),
      hasMore: result.hasMore,
    };
  }
  const result = await fetchList<TaskListItem>('/api/mobile/tasks', filters);
  const taskView = filters.view === 'done' ? 'done' : 'pending';
  return {
    items: result.items.map((task) => ({ kind: 'task', view: taskView, task })),
    hasMore: result.hasMore,
  };
}

async function fetchList<T>(path: string, filters: TaskCenterFilters): Promise<TaskPageResult<T>> {
  const page = filters.page ?? DEFAULT_PAGE;
  const size = filters.size ?? DEFAULT_SIZE;
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (path.endsWith('/tasks')) {
    params.set('view', filters.view);
  }
  if (filters.keyword) {
    params.set('keyword', filters.keyword);
  }
  if (filters.status) {
    params.set('status', filters.status);
  }
  return apiRequest<PagedResponse<T>>(`${path}?${params.toString()}`);
}

export type MobileHistoryItem = {
  id: number;
  fromNodeId?: string | null;
  toNodeId?: string | null;
  taskId?: number | null;
  action: string;
  operatorId?: number | null;
  comment?: string | null;
  createdAt: string;
};

export type RejectTarget = {
  nodeId: string;
  name: string;
};

export type MobileTaskFile = {
  id: string;
  name: string;
  contentType: string;
  size: number;
  contentUrl: string;
};

export type MobileTaskDetail = {
  task: TaskListItem;
  schema: unknown;
  formData: Record<string, unknown> | null;
  processSnapshot: unknown;
  history: MobileHistoryItem[];
  allowedActions: string[];
  rejectTargets: RejectTarget[];
  files: MobileTaskFile[];
};

export type TaskActionPayload = {
  comment?: string;
  rejectToNodeId?: string;
};

export async function fetchTaskDetail(taskId: number): Promise<MobileTaskDetail> {
  return apiRequest<MobileTaskDetail>(`/api/mobile/tasks/${taskId}`);
}

export async function runTaskAction(
  taskId: number,
  action: 'approve' | 'reject',
  payload: TaskActionPayload,
  idempotencyKey: string,
): Promise<void> {
  await apiRequest<void>(`/api/mobile/tasks/${taskId}/${action}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  });
}