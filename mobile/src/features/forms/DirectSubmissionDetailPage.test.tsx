import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DirectSubmissionDetailPage } from './DirectSubmissionDetailPage';

afterEach(() => vi.unstubAllGlobals());

describe('DirectSubmissionDetailPage', () => {
  it('renders a submitted non-workflow form as read-only detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: 7001,
      status: 'SUBMITTED',
      formCode: 'check',
      formName: '设备点检表',
      businessNo: 'CHECK-001',
      submittedAt: '2026-09-03T09:00:00+08:00',
      schema: [{ id: 'subject', type: 'text', label: '主题' }],
      formData: { subject: '完成' },
      files: [{ id: 'file-1', name: '点检照片.jpg', contentUrl: '/api/mobile/files/file-1/content', contentType: 'image/jpeg', size: 12 }],
    }), { status: 200, headers: { 'content-type': 'application/json' } })));

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/submissions/7001']}>
          <Routes><Route path="/submissions/:submissionId" element={<DirectSubmissionDetailPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('设备点检表')).toBeInTheDocument();
    expect(screen.getByText('已填报')).toBeInTheDocument();
    expect(screen.getByText('完成')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /点检照片.jpg/ })).toHaveAttribute('href', '/api/mobile/files/file-1/content');
    expect(screen.queryByText('审批记录')).not.toBeInTheDocument();
  });
});
