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
  'data' | 'schema'
> & {
  data: MobileFormValues;
  schema?: MobileSchemaNode[];
};

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
  idempotencyKey: string,
) {
  return apiRequest<number>('/api/mobile/drafts', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ formCode, data }),
  });
}

export async function updateMobileDraft(
  id: number,
  formCode: string,
  data: MobileFormValues,
  idempotencyKey: string,
) {
  return apiRequest<MobileDraft>(`/api/mobile/drafts/${id}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ formCode, data }),
  });
}

export async function deleteMobileDraft(id: number) {
  return apiRequest<void>(`/api/mobile/drafts/${id}`, {
    method: 'DELETE',
  });
}
