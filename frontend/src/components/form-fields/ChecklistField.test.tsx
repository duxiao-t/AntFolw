import { App } from 'antd';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChecklistField } from './ChecklistField';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock }));

beforeEach(() => {
  requestMock.mockResolvedValue(new Blob(['media']));
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:checklist-media'),
    revokeObjectURL: vi.fn(),
  });
});

afterEach(() => vi.unstubAllGlobals());

describe('desktop checklist field', () => {
  it('keeps the existing photo-only fill control', () => {
    const { container } = render(
      <App>
        <ChecklistField.Component
          node={{
            id: 'inspection',
            type: 'checklist',
            label: '设备检查',
            props: { items: [{ id: 'appearance', label: '设备外观' }] },
          }}
          mode="runtime-fill"
          value={[]}
        />
      </App>,
    );

    expect(screen.getByRole('button', { name: '添加照片' })).toBeInTheDocument();
    expect(container.querySelector<HTMLInputElement>('input[type="file"]'))
      .toHaveAttribute('accept', 'image/*');
  });

  it('renders each historical result, entered description, image, and video', async () => {
    const { container } = render(
      <App>
        <ChecklistField.Component
          node={{
            id: 'inspection',
            type: 'checklist',
            label: '设备检查',
            props: {
              items: [{ id: 'appearance', label: '设备外观', required: true }],
              results: [{ id: 'ok', label: '合格' }, { id: 'bad', label: '异常', color: '#D93025' }],
            },
          }}
          mode="readonly"
          value={[{
            itemId: 'appearance',
            result: 'bad',
            remark: '外壳有划痕',
            photos: [
              { id: 'p1', name: '现场.jpg', contentType: 'image/jpeg', contentUrl: '/files/p1' },
              { id: 'v1', name: '现场.mp4', contentType: 'video/mp4', contentUrl: '/files/v1' },
            ],
          }]}
        />
      </App>,
    );

    expect(screen.getByText('设备外观')).toBeInTheDocument();
    expect(screen.getByText('异常')).toBeInTheDocument();
    expect(screen.getByText('外壳有划痕')).toBeInTheDocument();
    await waitFor(() => expect(requestMock).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('img', { name: '现场.jpg' })).toBeInTheDocument();
    expect(container.querySelector('video')).toHaveAttribute('src', 'blob:checklist-media');
  });
});
