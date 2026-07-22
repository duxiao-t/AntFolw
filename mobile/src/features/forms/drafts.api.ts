import { apiRequest } from '../../shared/api/http';
import type { MobileFlowNode, MobileFormValues, MobileSchemaNode } from './schema/types';

export type MobileFormDetail = {
  code: string;
  name: string;
  version: number;
  schema: MobileSchemaNode[];
  process?: MobileFlowNode | null;
};

export type MobileDraft = {
  id: number;
  formCode: string;
  formName: string;
  formVersion: number;
  data: MobileFormValues;
  readOnly: boolean;
  createdAt?: string;
  updatedAt?: string;
  schema?: MobileSchemaNode[];
};

export async function fetchMobileForm(code: string) {
  return apiRequest<MobileFormDetail>(`/api/mobile/forms/${encodeURIComponent(code)}`);
}

export async function fetchMobileDraft(id: number) {
  return apiRequest<MobileDraft>(`/api/mobile/drafts/${id}`);
}

export async function fetchMobileDrafts() {
  return apiRequest<MobileDraft[]>('/api/mobile/drafts');
}

export async function createMobileDraft(formCode: string, data: MobileFormValues) {
  return apiRequest<number>('/api/mobile/drafts', {
    method: 'POST',
    body: JSON.stringify({ formCode, data }),
  });
}

export async function updateMobileDraft(id: number, formCode: string, data: MobileFormValues) {
  return apiRequest<MobileDraft>(`/api/mobile/drafts/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ formCode, data }),
  });
}

export async function deleteMobileDraft(id: number) {
  return apiRequest<void>(`/api/mobile/drafts/${id}`, {
    method: 'DELETE',
  });
}
