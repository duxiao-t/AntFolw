import type { PropsWithChildren } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandProvider } from '../features/branding/BrandProvider';
import { PlatformProvider } from './PlatformProvider';
import { setAuthController } from '../shared/api/auth';
import { useAuthStore } from '../features/auth/auth.store';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});

setAuthController({
  authorizationHeader: () => useAuthStore.getState().authorizationHeader(),
  refresh: () => useAuthStore.getState().refresh(),
  isAuthEndpoint: (path) => path.includes('/auth/'),
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
