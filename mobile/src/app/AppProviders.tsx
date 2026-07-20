import type { PropsWithChildren } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandProvider } from '../features/branding/BrandProvider';
import { PlatformProvider } from '../shared/platform/PlatformProvider';
import { setAuthController } from '../shared/api/auth';
import { useAuthStore } from '../features/auth/auth.store';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
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
        <PlatformProvider>{children}</PlatformProvider>
      </BrandProvider>
    </QueryClientProvider>
  );
}

export default AppProviders;
