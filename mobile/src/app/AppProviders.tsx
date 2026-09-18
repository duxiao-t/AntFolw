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
useAuthStore.subscribe((state, previous) => {
  if (state.status === 'unknown') return;
  if (previous.status === 'unknown' || state.user?.id !== previous.user?.id) {
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
