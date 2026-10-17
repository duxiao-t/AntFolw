import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { vi } from 'vitest';
import S3StoragePage from './S3Storage';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock }));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ header, content, children }: any) => (
    <main><h1>{header?.title}</h1>{content}{children}</main>
  ),
}));

/** 一份"当前生效配置"的固定响应：测试只关心页面如何展示它、以及自检交互，不依赖后端真实配置。 */
const CONFIG = {
  storage: 'minio',
  endpoint: 'http://minio:9000',
  bucket: 'antflow-files',
  region: '',
  createBucket: true,
  accessKeyMasked: 'AKIA****',
  secretConfigured: true,
  maxFileBytes: 104857600,
  maxVideoBytes: 104857600,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<ConfigProvider theme={{ token: { motion: false } }}><App>
    <QueryClientProvider client={client}><S3StoragePage /></QueryClientProvider>
  </App></ConfigProvider>);
}

beforeEach(() => {
  requestMock.mockReset();
});

it('shows the effective config with a masked access key', async () => {
  requestMock.mockResolvedValue(CONFIG);
  renderPage();

  expect(await screen.findByText('http://minio:9000')).toBeVisible();
  expect(screen.getByText('AKIA****')).toBeVisible();
  expect(screen.getByText(/图片\/文件 100 MB · 视频 100 MB/)).toBeVisible();
  // 密钥内容永不回传：只显示"已配置"
  expect(screen.getByText('已配置')).toBeVisible();
});

it('warns when the secret is missing instead of pretending it is fine', async () => {
  requestMock.mockResolvedValue({ ...CONFIG, secretConfigured: false, accessKeyMasked: '' });
  renderPage();

  expect(await screen.findByText('未配置，上传会失败')).toBeVisible();
});

it('runs the connectivity probe and reports a healthy storage', async () => {
  requestMock.mockImplementation((url: string, options?: any) =>
    options?.method === 'POST'
      ? Promise.resolve({ ok: true, bucketExists: true, writable: true, elapsedMs: 42, detail: '读写正常' })
      : Promise.resolve(CONFIG));
  renderPage();
  await screen.findByText('http://minio:9000');

  fireEvent.click(screen.getByRole('button', { name: '测试连接' }));

  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/settings/storage/test', { method: 'POST' }));
  expect(await screen.findByText('可用')).toBeVisible();
  expect(screen.getByText('读写正常')).toBeVisible();
  expect(screen.getByText('耗时 42 ms')).toBeVisible();
});

it('surfaces a failed probe with its reason', async () => {
  // 失败分支必须把后端的 detail 原样显示：管理员据此判断是"桶没建"还是"没写权限"。
  requestMock.mockImplementation((url: string, options?: any) =>
    options?.method === 'POST'
      ? Promise.resolve({ ok: false, bucketExists: true, writable: false, elapsedMs: 15, detail: '桶可访问但写入被拒绝，请检查账号权限' })
      : Promise.resolve(CONFIG));
  renderPage();
  await screen.findByText('http://minio:9000');

  fireEvent.click(screen.getByRole('button', { name: '测试连接' }));

  expect(await screen.findByText('不可用')).toBeVisible();
  expect(screen.getByText('桶可访问但写入被拒绝，请检查账号权限')).toBeVisible();
});
