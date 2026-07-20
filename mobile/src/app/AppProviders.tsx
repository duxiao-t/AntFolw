import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { PropsWithChildren } from 'react';
import { BrandProvider } from './BrandProvider';
import { PlatformProvider } from './PlatformProvider';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
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
