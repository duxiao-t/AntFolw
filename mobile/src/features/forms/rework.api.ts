import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';
import type { MobileTaskFile } from '../tasks/tasks.api';
import { collectMobileFileRefs } from './start.api';
import type {
  MobileFlowNode,
  MobileFormValues,
  MobileSchemaNode,
} from './schema/types';

export type ReworkTask = Omit<
  Contract<
    AntFlowAPI.ReworkTaskDto,
    'taskId' | 'instanceId' | 'formCode' | 'formName' | 'businessNo'
  >,
  'schema' | 'formData' | 'processSnapshot' | 'files'
> & {
  schema: MobileSchemaNode[];
  formData: MobileFormValues;
  processSnapshot: MobileFlowNode;
  files: MobileTaskFile[];
};

export type ReworkResult = Required<AntFlowAPI.ReworkResult>;

export function fetchReworkTask(taskId: number) {
  return apiRequest<ReworkTask>(`/api/mobile/rework-tasks/${taskId}`);
}

export function saveReworkTask(
  taskId: number,
  values: MobileFormValues,
  idempotencyKey: string,
) {
  return apiRequest<ReworkTask>(`/api/mobile/rework-tasks/${taskId}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({
      data: values,
      files: collectMobileFileRefs(values),
    }),
  });
}

export function resubmitReworkTask(
  taskId: number,
  values: MobileFormValues,
  idempotencyKey: string,
) {
  return apiRequest<ReworkResult>(
    `/api/mobile/rework-tasks/${taskId}/resubmit`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({
        data: values,
        files: collectMobileFileRefs(values),
      }),
    },
  );
}
