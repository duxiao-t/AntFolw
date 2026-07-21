import { apiRequest } from '../../shared/api/http';

export type MobileInstanceDetail = {
  id: number;
  status: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string;
  formName?: string;
  canWithdraw: boolean;
};

export async function fetchMobileInstanceDetail(instanceId: number) {
  return apiRequest<MobileInstanceDetail>(`/api/mobile/instances/${instanceId}`);
}
