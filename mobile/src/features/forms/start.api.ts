import { apiRequest } from '../../shared/api/http';
import type { MobileFormValues } from './schema/types';

export type StartResult = {
  instanceId: number;
  formDataId: number;
  businessNo: string;
  firstTaskIds: number[];
};

export type DirectSubmitResult = {
  dataId: number;
  businessNo: string;
};

export async function startMobileInstance({
  formCode,
  values,
  selfSelected,
  draftId,
  idempotencyKey,
}: {
  formCode: string;
  values: MobileFormValues;
  selfSelected: Record<string, number[]>;
  draftId: number | null;
  idempotencyKey: string;
}) {
  return apiRequest<StartResult>('/api/mobile/instances', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({
      formCode,
      data: values,
      selfSelected,
      draftId,
      files: collectMobileFileRefs(values),
    }),
  });
}

export async function submitMobileFormData({
  formCode,
  values,
}: {
  formCode: string;
  values: MobileFormValues;
}) {
  return apiRequest<DirectSubmitResult>('/api/forms/data', {
    method: 'POST',
    body: JSON.stringify({
      formCode,
      status: 'SUBMITTED',
      data: values,
      files: collectMobileFileRefs(values),
    }),
  });
}

export type MobileFileRef = {
  fileId: string;
  fieldId: string;
  sortOrder: number;
};

export function collectMobileFileRefs(values: MobileFormValues): MobileFileRef[] {
  const refs: MobileFileRef[] = [];
  for (const [fieldId, value] of Object.entries(values)) {
    collectValueFiles(value, fieldId, refs);
  }
  return refs;
}

function collectValueFiles(value: unknown, fieldId: string, refs: MobileFileRef[]) {
  if (!Array.isArray(value)) {
    return;
  }
  if (value.every(isMobileFileValue)) {
    value.forEach((file, index) => {
      refs.push({ fileId: file.id, fieldId, sortOrder: index });
    });
    return;
  }
  value.forEach((item) => {
    if (typeof item !== 'object' || item == null || Array.isArray(item)) {
      return;
    }
    for (const [nestedFieldId, nestedValue] of Object.entries(item)) {
      collectValueFiles(nestedValue, nestedFieldId, refs);
    }
  });
}

function isMobileFileValue(value: unknown): value is { id: string } {
  const file = value as {
    id?: unknown;
    contentType?: unknown;
    size?: unknown;
    sizeBytes?: unknown;
    contentUrl?: unknown;
    url?: unknown;
  };
  return typeof value === 'object'
    && value != null
    && !Array.isArray(value)
    && typeof file.id === 'string'
    && typeof file.contentType === 'string'
    && (typeof file.size === 'number' || typeof file.sizeBytes === 'number')
    && (typeof file.contentUrl === 'string' || typeof file.url === 'string');
}
