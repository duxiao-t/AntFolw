import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { RouterProvider } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AppProviders } from './AppProviders';
import { createTestRouter } from './router';

function wrapWithProviders(children: ReactNode, queryClient: QueryClient) {
  return (
    <QueryClientProvider client={queryClient}>
      <AppProviders>{children}</AppProviders>
    </QueryClientProvider>
  );
}

describe('router smoke tests', () => {
  it.each([
    ['/login', '登录 AntFlow'],
    ['/workbench', '工作台'],
    ['/tasks', '待办'],
    ['/profile', '我的'],
  ])('renders %s', async (path: string, title: string) => {
    const router = createTestRouter(path);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(wrapWithProviders(<RouterProvider router={router} />, queryClient));
    expect(await screen.findByText(title)).toBeInTheDocument();
  });
});
