import { useQuery } from '@tanstack/react-query';
import { apiRequest } from '../../shared/api/http';
import { queryKeys } from '../../shared/api/queryKeys';
import type { DeviceSession } from '../../shared/api/types';

export const getSessions = () => apiRequest<DeviceSession[]>('/api/auth/sessions');

export const revokeSession = (id: string) =>
  apiRequest<void>(`/api/auth/sessions/${id}`, { method: 'DELETE' });

export function useDeviceSessions() {
  return useQuery({
    queryKey: queryKeys.sessions,
    queryFn: getSessions,
    retry: 0,
    refetchOnWindowFocus: false,
  });
}
