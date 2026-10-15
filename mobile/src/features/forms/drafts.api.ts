import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';
import type {
  MobileFlowNode,
  MobileFormValues,
  MobileSchemaNode,
} from './schema/types';

export type MobileFormDetail = Omit<
  Contract<AntFlowAPI.MobileFormDto, 'code' | 'name' | 'version'>,
  'schema' | 'process' | 'settings' | 'starterFieldModes'
> & {
  schema: MobileSchemaNode[];
  settings?: Record<string, unknown>;
  process?: MobileFlowNode | null;
  starterFieldModes?: Record<string, 'EDITABLE' | 'READONLY' | 'HIDDEN'>;
  description?: string;
};

export type MobileDraft = Omit<
  Contract<
    AntFlowAPI.MobileDraftDto,
    'id' | 'formCode' | 'formName' | 'formVersion' | 'readOnly'
  >,
  'data' | 'schema' | 'companyId'
> & {
  data: MobileFormValues;
  schema?: MobileSchemaNode[];
  companyId?: AntFlowAPI.MobileDraftDto['companyId'] | null;
};

export type CompanyOption = Required<Pick<AntFlowAPI.Company, 'id' | 'name'>>;

/** 公司清单：只有"没有部门的全局管理员"需要它来显式选归属，所以只在那种情况下请求。 */
export async function fetchCompanies() {
  return apiRequest<CompanyOption[]>('/api/companies');
}

export async function fetchMobileForm(
  code: string,
  draftId?: number | null,
  reworkTaskId?: number | null,
) {
  const context = new URLSearchParams();
  if (draftId != null) context.set('draftId', String(draftId));
  if (reworkTaskId != null) context.set('reworkTaskId', String(reworkTaskId));
  const query = context.size ? `?${context}` : '';
  return apiRequest<MobileFormDetail>(
    `/api/mobile/forms/${encodeURIComponent(code)}${query}`,
  );
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
