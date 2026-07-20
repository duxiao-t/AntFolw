import { useQuery } from '@tanstack/react-query';
import { apiRequest } from '../../shared/api/http';
import { queryKeys } from '../../shared/api/queryKeys';
import type { MobileBootstrap, MobileApp, RecentProcess } from '../../shared/api/types';

export type { MobileBootstrap, MobileApp, RecentProcess };

export const MAX_FAVORITE_APPS = 8;
export const MAX_RECENT_PROCESSES = 3;

export async function fetchMobileBootstrap(): Promise<MobileBootstrap> {
  return apiRequest<MobileBootstrap>('/api/mobile/bootstrap');
}

export function useMobileBootstrap() {
  return useQuery({
    queryKey: queryKeys.bootstrap,
    queryFn: fetchMobileBootstrap,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    retry: 0,
  });
}

export function capFavorites(apps: ReadonlyArray<MobileApp>): MobileApp[] {
  if (apps.length <= MAX_FAVORITE_APPS) return [...apps];
  return apps.slice(0, MAX_FAVORITE_APPS);
}

export function capRecents(processes: ReadonlyArray<RecentProcess>): RecentProcess[] {
  if (processes.length <= MAX_RECENT_PROCESSES) return [...processes];
  return processes.slice(0, MAX_RECENT_PROCESSES);
}
