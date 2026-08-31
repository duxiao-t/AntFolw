import { apiRequest } from '../../shared/api/http';
import type { MobileSchemaNode } from '../forms/schema/types';

export type TaskView = 'pending' | 'done' | 'process';

export type TaskListItem = {
  id: number;
  instanceId: number;
  nodeId: string;
  formCode: string;
  formName: string;
  businessNo: string;
  applicantName: string;
  applicantEmployeeNo?: string;
  applicantDepartment?: string;
  nodeName: string;
  taskType: 'APPROVAL' | 'REWORK' | string;
  taskStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CC' | string;
  instanceStatus: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string;
  createdAt: string;
  readAt?: string | null;
};

export type StartedProcessItem = {
  id: number;
  status: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string;
  formName: string;
  businessNo?: string;
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

export type ApprovalSummary = {
  flowedCount: number;
  completedCount: number;
  processingCount: number;
  complete: boolean;
};

export type ApprovalRecord = {
  id: string;
  taskId?: number | null;
  nodeId?: string | null;
  nodeName: string;
  recordKind?: 'SUBMISSION' | 'APPROVAL' | 'REWORK' | 'CC' | 'AUTOMATION' | string;
  nodeType?: string | null;
  parallelId?: string | null;
  branchId?: string | null;
  operationKind?: 'TRANSFER' | 'DELEGATE' | 'ADD_ASSIGNEE' | string | null;
  sourceOperatorName?: string | null;
  status: 'SUBMITTED' | 'PROCESSING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'RESUBMITTED' | 'COMPLETED' | 'FAILED' | string;
  operatorName: string;
  employeeNo?: string | null;
  department?: string | null;
  comment?: string | null;
  receivedAt: string;
  completedAt?: string | null;
  roundNo?: number | null;
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
  schema: MobileSchemaNode[];
  formData: Record<string, unknown> | null;
  processSnapshot: unknown;
  history: MobileHistoryItem[];
  allowedActions: string[];
  rejectDisabled: boolean;
  rejectTargets: RejectTarget[];
  files: MobileTaskFile[];
  approvalSummary: ApprovalSummary;
  approvalRecords: ApprovalRecord[];
};

export type TaskActionPayload = {
  comment?: string;
  data?: Record<string, unknown>;
  rejectToNodeId?: string;
};

export async function fetchTaskDetail(taskId: number): Promise<MobileTaskDetail> {
  return apiRequest<MobileTaskDetail>(`/api/mobile/tasks/${taskId}`);
}

export async function markTaskRead(taskId: number): Promise<void> {
  await apiRequest<void>(`/api/mobile/tasks/${taskId}/read`, { method: 'POST' });
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
