import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ExportPage from './Export';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

function renderPage() {
  return render(<App><ExportPage /></App>);
}

describe('数据导出', () => {
  beforeEach(() => {
    request.mockReset();
    // happy-dom 没有 objectURL 实现，这里只需要它别炸。
    URL.createObjectURL = vi.fn(() => 'blob:test');
    URL.revokeObjectURL = vi.fn();
  });

  it('点下载之前先告诉你这次会导出多少行', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/form-data-count') return Promise.resolve({ total: 42, limit: 10000 });
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      return Promise.resolve(new Blob(['x']));
    });

    renderPage();

    expect(await screen.findByText('42')).toBeInTheDocument();
    expect(screen.getByText(/上限 10000 行/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /导出 CSV/ })).toBeEnabled();
  });

  it('超过上限时提前说清会截断，而不是让人以为导全了', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/form-data-count') return Promise.resolve({ total: 24000, limit: 10000 });
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      return Promise.resolve(new Blob(['x']));
    });

    renderPage();

    expect(await screen.findByText(/共有 24000 行，超过上限只导前 10000 行/)).toBeInTheDocument();
    // 显示的是"实际会拿到多少"，不是总数
    expect(screen.getByText('10000')).toBeInTheDocument();
  });

  it('没有数据时禁用下载并给出下一步', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/form-data-count') return Promise.resolve({ total: 0, limit: 10000 });
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      return Promise.resolve(new Blob(['x']));
    });

    renderPage();

    expect(await screen.findByText(/当前条件没有数据/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /导出 CSV/ })).toBeDisabled();
  });

  it('导出时把筛选（含时区）与 blob 响应类型一起发出去', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/form-data-count') return Promise.resolve({ total: 3, limit: 10000 });
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [{ id: 5, name: '登记表' }] });
      return Promise.resolve(new Blob(['业务单号\n']));
    });

    renderPage();
    await screen.findByText('3');

    fireEvent.change(screen.getByLabelText('提交人'), { target: { value: ' 张三 ' } });
    // 改筛选会重新估算行数，这期间按钮是禁用的——等它回来再点（这也是用户会遇到的节奏）。
    await waitFor(() => expect(screen.getByRole('button', { name: /导出 CSV/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /导出 CSV/ }));

    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/forms/data/admin/export',
      expect.objectContaining({
        responseType: 'blob',
        params: expect.objectContaining({
          submitterKeyword: '张三',
          tzOffsetMinutes: expect.any(Number),
          from: expect.any(String),
          to: expect.any(String),
        }),
      })));
  });
});
