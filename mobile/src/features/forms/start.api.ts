import { apiRequest } from '../../shared/api/http';
import type { MobileFormValues } from './schema/types';

export type StartResult = {
  instanceId: number;
  formDataId: number;
  firstTaskIds: number[];
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

type MobileFileRef = {
  fileId: string;
  fieldId: string;
  sortOrder: number;
};

function collectMobileFileRefs(values: MobileFormValues): MobileFileRef[] {
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
  return typeof value === 'object'
    && value != null
    && !Array.isArray(value)
    && typeof (value as { id?: unknown }).id === 'string'
    && typeof (value as { contentType?: unknown }).contentType === 'string'
    && typeof (value as { sizeBytes?: unknown }).sizeBytes === 'number';
}
