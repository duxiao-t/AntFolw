import { App } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChecklistField } from './ChecklistField';
import { FileUploadField } from './FileUploadField';
import { ImageUploadField } from './ImageUploadField';
import { VideoUploadField } from './VideoUploadField';
import { firstVisibleValidationError } from '../../registry/displayConditions';
import type { SchemaNode } from '../../registry/types';

const media = vi.hoisted(() => ({ upload: vi.fn(), remove: vi.fn() }));

// 只把网络部分替掉，`toMediaFiles` / `withPendingUpload` 这些纯函数要留真的（渲染路径会用到）。
vi.mock('./nativeMedia', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./nativeMedia')>()),
  uploadMediaFile: media.upload,
  deleteNativeFile: media.remove,
}));

type Change = (value: unknown) => void;

function dto(overrides: Record<string, unknown> = {}) {
  return {
    id: 'file-1', name: 'photo.jpg', contentUrl: '/api/mobile/files/file-1/content',
    contentType: 'image/jpeg', size: 12, status: 'READY', ...overrides,
  };
}

function imageNode(props: Record<string, unknown> = {}): SchemaNode {
  return { id: 'photo', type: 'image_upload', label: '现场照片', props };
}

async function pickFile(container: HTMLElement, file = new File(['x'], 'photo.jpg', { type: 'image/jpeg' })) {
  const input = container.querySelector<HTMLInputElement>('input[type="file"]');
  expect(input).not.toBeNull();
  fireEvent.change(input as HTMLInputElement, { target: { files: [file] } });
}

/** happy-dom 不解码媒体，时长只能自己塞：`createElement('video')` 返回一个"元数据已就绪"的替身。 */
function stubVideoDuration(seconds: number) {
  const createElement = document.createElement.bind(document);
  const spy = vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
    const element = createElement(tag);
    if (tag !== 'video') return element;
    Object.defineProperty(element, 'duration', { value: seconds, configurable: true });
    queueMicrotask(() => element.dispatchEvent(new Event('loadedmetadata')));
    return element;
  }) as typeof document.createElement);
  return () => spy.mockRestore();
}

describe('桌面端媒体上传', () => {
  beforeEach(() => {
    media.upload.mockReset();
    media.remove.mockReset();
    media.upload.mockResolvedValue(dto());
  });

  it('真的发上传请求，并把服务端 DTO 写进表单值', async () => {
    const onChange = vi.fn<Change>();
    const { container } = render(
      <App>
        <ImageUploadField.Component node={imageNode()} mode="runtime-fill"
          value={[]} onChange={onChange} />
      </App>,
    );

    await pickFile(container);

    await waitFor(() => expect(media.upload).toHaveBeenCalledTimes(1));
    // 契约：`Fill.collectFileRefs` 只认带字符串 id + contentType 的项，形状不对就是静默丢附件。
    await waitFor(() => expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ id: 'file-1', contentType: 'image/jpeg' }),
    ]));
  });

  it('开了水印的字段把水印参数一起带上（服务端没文案会直接拒）', async () => {
    const { container } = render(
      <App>
        <ImageUploadField.Component
          node={imageNode({ watermark: true, watermarkText: '现场留证' })}
          mode="runtime-fill" value={[]} onChange={vi.fn()} />
      </App>,
    );

    await pickFile(container);

    await waitFor(() => expect(media.upload).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ watermark: true, watermarkText: '现场留证' }),
    ));
  });

  it('没开水印时不带水印参数', async () => {
    const { container } = render(
      <App>
        <FileUploadField.Component node={{ id: 'doc', type: 'file_upload', label: '附件', props: {} }}
          mode="runtime-fill" value={[]} onChange={vi.fn()} />
      </App>,
    );

    await pickFile(container);

    await waitFor(() => expect(media.upload).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ watermark: undefined }),
    ));
  });

  it('上传失败时保留可重试的行，且不把半成品写进表单值', async () => {
    media.upload.mockRejectedValue(new Error('服务器缺少 ffmpeg，无法为图片/视频添加水印'));
    const onChange = vi.fn<Change>();
    const { container } = render(
      <App>
        <ImageUploadField.Component node={imageNode()} mode="runtime-fill"
          value={[]} onChange={onChange} />
      </App>,
    );

    await pickFile(container);

    expect(await screen.findByText(/服务器缺少 ffmpeg/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
    // 只有"标记还在传"的那次写入，没有任何 DTO 落进值里。
    onChange.mock.calls.forEach(([value]) => {
      expect(Array.isArray(value) ? value.filter(Boolean) : []).toEqual([]);
    });
  });

  it('超过 maxDuration 的视频在入队前就被挡下（不上传、不进表单值）', async () => {
    const restore = stubVideoDuration(120);
    const onChange = vi.fn<Change>();
    const { container } = render(
      <App>
        <VideoUploadField.Component
          node={{ id: 'clip', type: 'video_upload', label: '视频', props: { maxDuration: 60 } }}
          mode="runtime-fill" value={[]} onChange={onChange} />
      </App>,
    );

    try {
      await pickFile(container, new File(['x'], 'clip.mp4', { type: 'video/mp4' }));

      expect(await screen.findByText(/视频不能超过 60 秒/)).toBeInTheDocument();
      expect(media.upload).not.toHaveBeenCalled();
      expect(onChange).not.toHaveBeenCalled();
    } finally {
      restore();
    }
  });

  it('删除已上传的文件会调服务端删除，并从值里移除', async () => {
    const onChange = vi.fn<Change>();
    const { container } = render(
      <App>
        <VideoUploadField.Component
          node={{ id: 'clip', type: 'video_upload', label: '视频', props: {} }}
          mode="runtime-fill" value={[dto({ contentType: 'video/mp4' })]} onChange={onChange} />
      </App>,
    );

    const removeButton = container.querySelector<HTMLElement>('.ant-upload-list-item-actions .anticon-delete')
      ?? container.querySelector<HTMLElement>('[aria-label="Delete"]');
    expect(removeButton).not.toBeNull();
    fireEvent.click(removeButton as HTMLElement);

    await waitFor(() => expect(media.remove).toHaveBeenCalledWith('file-1'));
    await waitFor(() => expect(onChange).toHaveBeenCalledWith([]));
  });

  it('检查项照片上传后写回该检查项的 images', async () => {
    const onChange = vi.fn<Change>();
    const { container } = render(
      <App>
        <ChecklistField.Component
          node={{
            id: 'inspection', type: 'checklist', label: '5S检查表',
            props: { items: [{ id: 'appearance', label: '设备外观' }], allowDescription: true },
          }}
          mode="runtime-fill" value={[]} onChange={onChange} />
      </App>,
    );

    await pickFile(container);

    await waitFor(() => expect(media.upload).toHaveBeenCalled());
    await waitFor(() => expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({
        id: 'appearance',
        images: [expect.objectContaining({ id: 'file-1', contentType: 'image/jpeg' })],
      }),
    ]));
  });
});

describe('提交前的媒体校验', () => {
  it('还在上传的照片挡住提交（已传完一张、另一张还在传）', () => {
    const node = imageNode({ required: true });
    const pending = [dto()] as unknown[];
    Object.defineProperty(pending, Symbol.for('antflowPendingUpload'), { value: true });

    expect(firstVisibleValidationError([node], { photo: pending }))
      .toBe('仍有文件未完成上传');
  });

  it('服务端还没处理完（视频加水印）或已失败的文件挡住提交', () => {
    const node = { id: 'clip', type: 'video_upload', label: '视频', props: {} } as SchemaNode;

    expect(firstVisibleValidationError([node], {
      clip: [dto({ contentType: 'video/mp4', status: 'PROCESSING' })],
    })).toContain('还在处理中');
    expect(firstVisibleValidationError([node], {
      clip: [dto({ contentType: 'video/mp4', status: 'FAILED' })],
    })).toContain('处理失败');
    // READY 的正常提交
    expect(firstVisibleValidationError([node], { clip: [dto({ contentType: 'video/mp4' })] }))
      .toBeNull();
  });

  it('检查项里某一张照片还在传也要挡住（照片在 items[].images 里，不是 children）', () => {
    const node = {
      id: 'inspection', type: 'checklist', label: '5S检查表',
      props: { items: [{ id: 'appearance', label: '设备外观' }] },
    } as SchemaNode;
    const images = [dto()] as unknown[];
    Object.defineProperty(images, Symbol.for('antflowPendingUpload'), { value: true });

    expect(firstVisibleValidationError([node], {
      inspection: [{ id: 'appearance', status: 'pass', description: '', images }],
    })).toBe('仍有检查项照片未完成上传');
  });
});
