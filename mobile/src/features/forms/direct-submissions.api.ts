import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';
import type { MobileFile } from '../../shared/api/types';
import type { MobileFormValues, MobileSchemaNode } from './schema/types';

export type MobileDirectSubmission = Omit<
  Contract<
    AntFlowAPI.MobileDirectSubmissionDetailDto,
    'id' | 'status' | 'formCode' | 'formName' | 'submittedAt'
  >,
  'businessNo' | 'schema' | 'formData' | 'files'
> & {
  businessNo?: AntFlowAPI.MobileDirectSubmissionDetailDto['businessNo'] | null;
  schema: MobileSchemaNode[];
  formData: MobileFormValues;
  files: MobileFile[];
};

export function fetchMobileDirectSubmission(id: number) {
  return apiRequest<MobileDirectSubmission>(`/api/mobile/submissions/${id}`);
}
