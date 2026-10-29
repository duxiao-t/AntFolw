import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';
import type { MobileSchemaNode } from '../forms/schema/types';
import type { ApprovalRecord, ApprovalSummary } from '../tasks/tasks.api';

export type MobileHistoryItem = import('../tasks/tasks.api').MobileHistoryItem;

export type MobileProcessFile = import('../tasks/tasks.api').MobileTaskFile;

export type MobileInstanceDetail = Omit<
  Contract<
    AntFlowAPI.MobileInstanceDetailDto,
    'visibility' | 'id' | 'status' | 'canWithdraw'
  >,
  | 'formName'
  | 'businessNo'
  | 'applicantName'
  | 'applicantEmployeeNo'
  | 'applicantDepartment'
  | 'currentNodeName'
  | 'schema'
  | 'formData'
  | 'processSnapshot'
  | 'history'
  | 'files'
  | 'approvalSummary'
  | 'approvalRecords'
> & {
  formName?: string | null;
  businessNo?: string | null;
  applicantName?: string | null;
  applicantEmployeeNo?: string | null;
  applicantDepartment?: string | null;
  currentNodeName?: string | null;
  schema?: MobileSchemaNode[] | null;
  processSnapshot?: unknown;
  formData?: Record<string, unknown> | null;
  history?: MobileHistoryItem[];
  files?: MobileProcessFile[];
  approvalSummary: ApprovalSummary;
  approvalRecords: ApprovalRecord[];
};

export async function fetchMobileInstanceDetail(instanceId: number) {
  return apiRequest<MobileInstanceDetail>(
    `/api/mobile/instances/${instanceId}`,
  );
}

export async function withdrawMobileInstance(
  instanceId: number,
  idempotencyKey: string,
) {
  await apiRequest<void>(`/api/mobile/instances/${instanceId}/withdraw`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({}),
  });
}

/** 作废撤回后的「待修改」申请（软终态，从本人视图隐藏）。 */
export async function discardMobileInstance(
  instanceId: number,
  idempotencyKey: string,
) {
  await apiRequest<void>(`/api/mobile/instances/${instanceId}/discard`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({}),
  });
}
