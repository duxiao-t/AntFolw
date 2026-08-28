import { apiRequest } from '../../shared/api/http';
import type { MobileSchemaNode } from '../forms/schema/types';
import type { ApprovalRecord, ApprovalSummary } from '../tasks/tasks.api';

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

export type MobileProcessFile = {
  id: string;
  name: string;
  contentType: string;
  size: number;
  contentUrl: string;
};

export type MobileInstanceDetail = {
  id: number;
  status: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string;
  formName?: string;
  businessNo?: string;
  applicantName?: string | null;
  applicantEmployeeNo?: string | null;
  applicantDepartment?: string | null;
  startedAt?: string;
  currentNodeName?: string | null;
  schema?: MobileSchemaNode[];
  formData?: Record<string, unknown> | null;
  processSnapshot?: unknown;
  history?: MobileHistoryItem[];
  canWithdraw: boolean;
  files?: MobileProcessFile[];
  approvalSummary: ApprovalSummary;
  approvalRecords: ApprovalRecord[];
};

export async function fetchMobileInstanceDetail(instanceId: number) {
  return apiRequest<MobileInstanceDetail>(`/api/mobile/instances/${instanceId}`);
}

export async function withdrawMobileInstance(instanceId: number, idempotencyKey: string) {
  await apiRequest<void>(`/api/mobile/instances/${instanceId}/withdraw`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({}),
  });
}
