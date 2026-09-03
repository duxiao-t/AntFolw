import { apiRequest } from '../../shared/api/http';
import type { MobileFile } from '../../shared/api/types';
import type { MobileFormValues, MobileSchemaNode } from './schema/types';

export type MobileDirectSubmission = {
  id: number;
  status: 'SUBMITTED' | string;
  formCode: string;
  formName: string;
  businessNo?: string | null;
  submittedAt: string;
  schema: MobileSchemaNode[];
  formData: MobileFormValues;
  files: MobileFile[];
};

export function fetchMobileDirectSubmission(id: number) {
  return apiRequest<MobileDirectSubmission>(`/api/mobile/submissions/${id}`);
}
