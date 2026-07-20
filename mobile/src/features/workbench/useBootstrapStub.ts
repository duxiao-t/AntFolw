import { useQuery } from '@tanstack/react-query';
import type { MobileBootstrap } from '../../shared/api/types';
import { queryKeys } from '../../shared/api/queryKeys';
import { apiRequest } from '../../shared/api/http';

/**
 * Task 6 stub: returns no data so the shell can render without a live bootstrap
 * endpoint. Task 7 will replace this with the real fetcher that returns user,
 * favourite apps, recent processes, and a pending task count.
 */
export async function fetchMobileBootstrapStub(): Promise<MobileBootstrap | null> {
  void apiRequest;
  return null;
}

export function useMobileBootstrapStub() {
  return useQuery({
    queryKey: queryKeys.bootstrap,
    queryFn: fetchMobileBootstrapStub,
    enabled: false,
    staleTime: 60_000,
  });
}
