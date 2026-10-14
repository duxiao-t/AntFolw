import { apiRequest } from '../../shared/api/http';
import type { MobileFlowNode, MobileFormValues, MobileSchemaNode } from './schema/types';

export type MobileFormDetail = {
  code: string;
  name: string;
  description?: string;
  version: number;
  schema: MobileSchemaNode[];
  settings?: Record<string, unknown>;
  process?: MobileFlowNode | null;
  starterFieldModes?: Record<string, 'EDITABLE' | 'READONLY' | 'HIDDEN'>;
};

export type MobileDraft = {
  id: number;
  formCode: string;
  formName: string;
  formVersion: number;
  data: MobileFormValues;
  readOnly: boolean;
  /** P4a 归属快照：草稿建好后就固定了，提交时按它落库（不按当前部门重算）。 */
  companyId?: number | null;
  companyInferred?: boolean;
  createdAt?: string;
  updatedAt?: string;
  schema?: MobileSchemaNode[];
};

export type CompanyOption = {
  id: number;
  name: string;
};

/** 公司清单：只有"没有部门的全局管理员"需要它来显式选归属，所以只在那种情况下请求。 */
export async function fetchCompanies() {
  return apiRequest<CompanyOption[]>('/api/companies');
}

export async function fetchMobileForm(code: string, draftId?: number | null, reworkTaskId?: number | null) {
  const context = new URLSearchParams();
  if (draftId != null) context.set('draftId', String(draftId));
  if (reworkTaskId != null) context.set('reworkTaskId', String(reworkTaskId));
  const query = context.size ? `?${context}` : '';
  return apiRequest<MobileFormDetail>(`/api/mobile/forms/${encodeURIComponent(code)}${query}`);
}

export async function fetchMobileDraft(id: number) {
  return apiRequest<MobileDraft>(`/api/mobile/drafts/${id}`);
}

export async function fetchMobileDrafts() {
  return apiRequest<MobileDraft[]>('/api/mobile/drafts');
}

export async function createMobileDraft(
  formCode: string,
  data: MobileFormValues,
  companyId: number | null,
  idempotencyKey: string,
) {
  return apiRequest<number>('/api/mobile/drafts', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ formCode, data, companyId }),
  });
}

export async function updateMobileDraft(
  id: number,
  formCode: string,
  data: MobileFormValues,
  companyId: number | null,
  idempotencyKey: string,
) {
  return apiRequest<MobileDraft>(`/api/mobile/drafts/${id}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ formCode, data, companyId }),
  });
}

export async function deleteMobileDraft(id: number) {
  return apiRequest<void>(`/api/mobile/drafts/${id}`, {
    method: 'DELETE',
  });
}
