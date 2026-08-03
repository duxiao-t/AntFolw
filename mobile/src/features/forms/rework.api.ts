import { apiRequest } from '../../shared/api/http';
import type { MobileTaskFile } from '../tasks/tasks.api';
import { collectMobileFileRefs } from './start.api';
import type { MobileFormValues } from './schema/types';

export type ReworkTask = {
  taskId: number;
  instanceId: number;
  formCode: string;
  formName: string;
  businessNo: string;
  schema: unknown;
  formData: MobileFormValues;
  processSnapshot: unknown;
  files: MobileTaskFile[];
};

export type ReworkResult = {
  instanceId: number;
  formDataId: number;
  businessNo: string;
  firstTaskIds: number[];
};

export function fetchReworkTask(taskId: number) {
  return apiRequest<ReworkTask>(`/api/mobile/rework-tasks/${taskId}`);
}

export function saveReworkTask(taskId: number, values: MobileFormValues) {
  return apiRequest<ReworkTask>(`/api/mobile/rework-tasks/${taskId}`, {
    method: 'PUT',
    body: JSON.stringify({ data: values, files: collectMobileFileRefs(values) }),
  });
}

export function resubmitReworkTask(
  taskId: number,
  values: MobileFormValues,
  idempotencyKey: string,
) {
  return apiRequest<ReworkResult>(`/api/mobile/rework-tasks/${taskId}/resubmit`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ data: values, files: collectMobileFileRefs(values) }),
  });
}
