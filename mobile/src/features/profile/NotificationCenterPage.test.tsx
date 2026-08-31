import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotificationCenterPage } from './NotificationCenterPage';

afterEach(() => vi.unstubAllGlobals());

describe('NotificationCenterPage', () => {
  it('shows durable workflow messages and marks an unread message read', async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/12/read')) return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(new Response(JSON.stringify({
        items: [{
          id: 12,
          eventType: 'APPROVAL_INVALIDATED',
          title: '您的审批已作废',
          createdAt: '2026-08-30T10:00:00+08:00',
        }],
        hasMore: false,
        unreadCount: 1,
      }), { status: 200 }));
    });
    vi.stubGlobal('fetch', fetchMock);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter><NotificationCenterPage /></MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('您的审批已作废')).toBeInTheDocument();
    expect(screen.getByText('1 条未读消息')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /您的审批已作废/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/notifications/12/read',
      expect.objectContaining({ method: 'POST' }),
    ));
  });
});
