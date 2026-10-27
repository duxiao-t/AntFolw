import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';
import type { MobileFormValues } from './schema/types';

export type StartResult = Required<AntFlowAPI.MobileStartResult>;

export type DirectSubmitResult = Required<AntFlowAPI.SubmitResult>;

export type ApprovalPreview = Omit<AntFlowAPI.ApprovalPreviewDto, 'nodes'> & {
  nodes: ApprovalPreviewNode[];
};

export type ApprovalPreviewNode = Omit<
  Contract<
    AntFlowAPI.ApprovalPreviewNodeDto,
    'nodeId' | 'nodeName' | 'approvalMode' | 'deferred'
  >,
  'assignees'
> & { assignees: Required<AntFlowAPI.ApprovalPreviewAssigneeDto>[] };

export function fetchApprovalPreview({
  formCode,
  values,
  selfSelected,
  reworkTaskId,
  draftId,
}: {
  formCode: string;
  values: MobileFormValues;
  selfSelected: Record<string, number[]>;
  reworkTaskId: number | null;
  draftId?: number | null;
}) {
  return apiRequest<ApprovalPreview>(
    `/api/mobile/forms/${encodeURIComponent(formCode)}/approval-preview`,
    {
      method: 'POST',
      body: JSON.stringify({
        data: values,
        selfSelected,
        reworkTaskId,
        ...(draftId != null ? { draftId } : {}),
      }),
    },
  );
}

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
  draftId,
}: {
  formCode: string;
  values: MobileFormValues;
  draftId: number | null;
}) {
  // 移动端专用入口：桌面端提交走 /api/forms/data，移动端走 /api/mobile/submissions
  return apiRequest<DirectSubmitResult>('/api/mobile/submissions', {
    method: 'POST',
    body: JSON.stringify({
      formCode,
      status: 'SUBMITTED',
      data: values,
      draftId,
      files: collectMobileFileRefs(values),
    }),
  });
}

export type MobileFileRef = Required<AntFlowAPI.MobileFileRef>;

export function collectMobileFileRefs(
  values: MobileFormValues,
): MobileFileRef[] {
  const refs: MobileFileRef[] = [];
  for (const [fieldId, value] of Object.entries(values)) {
    collectValueFiles(value, fieldId, refs);
  }
  return refs;
}

function collectValueFiles(
  value: unknown,
  fieldId: string,
  refs: MobileFileRef[],
) {
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
  return (
    typeof value === 'object' &&
    value != null &&
    !Array.isArray(value) &&
    typeof file.id === 'string' &&
    typeof file.contentType === 'string' &&
    (typeof file.size === 'number' || typeof file.sizeBytes === 'number') &&
    (typeof file.contentUrl === 'string' || typeof file.url === 'string')
  );
}
