import type { PropsWithChildren } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandProvider } from '../features/branding/BrandProvider';
import { PlatformProvider } from '../shared/platform/PlatformProvider';
import { NetworkStatusProvider } from '../shared/recovery/NetworkStatusProvider';
import { setAuthController } from '../shared/api/auth';
import { useAuthStore } from '../features/auth/auth.store';
import { AuthBootstrap } from '../features/auth/AuthBootstrap';
import { GlobalErrorBoundary } from './GlobalErrorBoundary';
import { WebVitalsReporter } from '../shared/telemetry/WebVitalsReporter';
import { useFavoriteDraftStore } from '../features/workbench/apps.store';
import { useSubmitFlowStore } from '../features/forms/submitFlow.store';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});

// Clear synchronously before the next account can render any prior account's server data.
// 角色集变化也要清：同一个人被加/减角色后，refresh() 拿回的 bootstrap 已经是新的，
// 但 react-query 里按旧权限缓存的响应（工作台应用、待办计数…）不会自己失效。
function rolesKey(roles: readonly string[] | undefined): string {
  return [...(roles ?? [])].sort().join(',');
}

useAuthStore.subscribe((state, previous) => {
  if (state.status === 'unknown') return;
  const accountChanged = previous.status === 'unknown'
    || state.user?.id !== previous.user?.id
    || rolesKey(state.user?.roles) !== rolesKey(previous.user?.roles);
  if (accountChanged) {
    queryClient.clear();
    useFavoriteDraftStore.getState().reset([]);
    useSubmitFlowStore.getState().reset();
  }
});

export function isRefreshExcludedAuthEndpoint(path: string): boolean {
  const pathname = path.split('?')[0];
  return (
    pathname === '/api/auth/login' ||
    pathname === '/api/auth/refresh' ||
    pathname === '/api/auth/logout'
  );
}

setAuthController({
  authorizationHeader: () => useAuthStore.getState().authorizationHeader(),
  refresh: () => useAuthStore.getState().refresh(),
  isAuthEndpoint: isRefreshExcludedAuthEndpoint,
});

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={queryClient}>
      <BrandProvider>
        <PlatformProvider>
          <NetworkStatusProvider>
            <AuthBootstrap>
              <GlobalErrorBoundary>
                <WebVitalsReporter />
                {children}
              </GlobalErrorBoundary>
            </AuthBootstrap>
          </NetworkStatusProvider>
        </PlatformProvider>
      </BrandProvider>
    </QueryClientProvider>
  );
}

export default AppProviders;
