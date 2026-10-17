import { useQuery } from '@tanstack/react-query';
import { apiRequest } from '../../shared/api/http';
import { queryKeys } from '../../shared/api/queryKeys';
import type { DeviceSession } from '../../shared/api/types';

export const getSessions = () => apiRequest<DeviceSession[]>('/api/auth/sessions');

export const revokeSession = (id: string) =>
  apiRequest<void>(`/api/auth/sessions/${id}`, { method: 'DELETE' });

export type PasswordChangeInput = {
  currentPassword: string;
  newPassword: string;
};

/** 自助改密：接口与桌面端共用同一个端点，成功后其它设备会被下线。 */
export const changeOwnPassword = (input: PasswordChangeInput) =>
  apiRequest<void>('/api/account/password', {
    method: 'POST',
    body: JSON.stringify(input),
  });

export function useDeviceSessions() {
  return useQuery({
    queryKey: queryKeys.sessions,
    queryFn: getSessions,
    retry: 0,
    refetchOnWindowFocus: false,
  });
}
