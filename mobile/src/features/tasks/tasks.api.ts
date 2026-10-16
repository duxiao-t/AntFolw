import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';
import type { MobileSchemaNode } from '../forms/schema/types';

export type TaskView = 'pending' | 'done' | 'process';

export type TaskListItem = Contract<
  Omit<AntFlowAPI.MobileTaskDto, 'readAt'>,
  | 'id'
  | 'instanceId'
  | 'nodeId'
  | 'formCode'
  | 'formName'
  | 'businessNo'
  | 'applicantName'
  | 'nodeName'
  | 'taskType'
  | 'taskStatus'
  | 'instanceStatus'
  | 'createdAt'
> & { readAt?: string | null };

export type StartedProcessItem = Contract<
  Omit<AntFlowAPI.MobileInitiatedDto, 'finishedAt'>,
  'kind' | 'id' | 'status' | 'formName' | 'startedAt'
> & { finishedAt?: string | null };

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

export async function fetchTaskCenterItems(
  filters: TaskCenterFilters,
): Promise<TaskPageResult<TaskCenterItem>> {
  if (filters.view === 'process') {
    const result = await fetchList<StartedProcessItem>(
      '/api/mobile/initiated',
      filters,
    );
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

async function fetchList<T>(
  path: string,
  filters: TaskCenterFilters,
): Promise<TaskPageResult<T>> {
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

export type MobileHistoryItem = Contract<
  {
    [K in keyof AntFlowAPI.MobileHistoryDto]:
      | AntFlowAPI.MobileHistoryDto[K]
      | null;
  },
  'id' | 'action' | 'createdAt'
> &
  Required<Pick<AntFlowAPI.MobileHistoryDto, 'id' | 'action' | 'createdAt'>>;

export type RejectTarget = Required<AntFlowAPI.RejectTargetDto>;

export type ApprovalSummary = Required<AntFlowAPI.ApprovalSummaryDto>;

export type ApprovalRecord = Omit<
  {
    [K in keyof AntFlowAPI.ApprovalRecordDto]:
      | AntFlowAPI.ApprovalRecordDto[K]
      | null;
  },
  'id' | 'nodeName' | 'status' | 'operatorName' | 'receivedAt'
> &
  Required<
    Pick<
      AntFlowAPI.ApprovalRecordDto,
      'id' | 'nodeName' | 'status' | 'operatorName' | 'receivedAt'
    >
  >;

export type MobileTaskFile = Required<
  Pick<
    AntFlowAPI.MobileFileDto,
    'id' | 'name' | 'contentType' | 'size' | 'contentUrl'
  >
>;

export type MobileTaskDetail = Omit<
  AntFlowAPI.MobileTaskDetailDto,
  | 'task'
  | 'schema'
  | 'formData'
  | 'processSnapshot'
  | 'history'
  | 'rejectTargets'
  | 'files'
  | 'approvalSummary'
  | 'approvalRecords'
  | 'commentPresets'
> & {
  task: TaskListItem;
  schema: MobileSchemaNode[];
  processSnapshot?: unknown;
  formData: Record<string, unknown> | null;
  history: MobileHistoryItem[];
  allowedActions: string[];
  rejectDisabled: boolean;
  rejectTargets: RejectTarget[];
  files: MobileTaskFile[];
  approvalSummary: ApprovalSummary;
  approvalRecords: ApprovalRecord[];
  commentPresets?: Required<AntFlowAPI.ApprovalCommentPresets>;
};

export type TaskActionPayload = Omit<
  AntFlowAPI.MobileTaskActionRequest,
  'data'
> & { data?: Record<string, unknown> };

export async function fetchTaskDetail(
  taskId: number,
): Promise<MobileTaskDetail> {
  return apiRequest<MobileTaskDetail>(`/api/mobile/tasks/${taskId}`);
}

export async function markTaskRead(taskId: number): Promise<void> {
  await apiRequest<void>(`/api/mobile/tasks/${taskId}/read`, {
    method: 'POST',
  });
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
