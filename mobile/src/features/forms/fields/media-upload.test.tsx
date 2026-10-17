import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MobileFileDto } from '../files.api';
import { FileStillProcessingError } from '../files.api';
import type { MobileSchemaNode } from '../schema/types';
import { FileUploadField, isHeicFile, maxCountError, uploadExtraFields, videoDurationError } from './FileUploadField';
import { ImageUploadField } from './ImageUploadField';
import { VideoUploadField } from './VideoUploadField';

// 只替掉网络调用，`FileStillProcessingError` 的类身份要留真的——组件靠 `instanceof` 分叉。
const files = vi.hoisted(() => ({ upload: vi.fn(), wait: vi.fn(), remove: vi.fn() }));
vi.mock('../files.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../files.api')>()),
  uploadMobileFile: files.upload,
  waitForProcessedFile: files.wait,
  deleteMobileFile: files.remove,
}));

function baseProps(node: MobileSchemaNode, value: unknown, onValueChange = vi.fn()) {
  return { node, value, values: { [node.id]: value }, mode: 'fill' as const, error: undefined, onValueChange };
}

describe('media upload fields', () => {
  beforeEach(() => {
    files.upload.mockReset();
    files.wait.mockReset();
    files.remove.mockReset();
  });
  it('image field caps at 20 images by default', () => {
    const node: MobileSchemaNode = { id: 'photo', type: 'image_upload', label: '照片' };
    expect(maxCountError(node, 19)).toBeNull();
    expect(maxCountError(node, 20)).toBe('最多上传 20 张图片');
  });

  it('image field uses a configured max count', () => {
    const node: MobileSchemaNode = { id: 'photo', type: 'image_upload', label: '照片', props: { maxCount: 3 } };
    expect(maxCountError(node, 3)).toBe('最多上传 3 张图片');
  });

  it('video field caps duration at 60 seconds by default', () => {
    const node: MobileSchemaNode = { id: 'video', type: 'video_upload', label: '视频' };
    expect(videoDurationError(node, 60)).toBeNull();
    expect(videoDurationError(node, 60.5)).toBe('视频不能超过 60 秒');
    expect(videoDurationError(node, 1)).toBeNull();
  });

  it('sends watermark params only when watermark is enabled', () => {
    expect(uploadExtraFields({ id: 'photo', type: 'image_upload', label: '照片', props: { watermark: false } })).toBeUndefined();
    expect(uploadExtraFields({ id: 'photo', type: 'image_upload', label: '照片', props: { watermark: true, watermarkText: ' 出差留痕 ' } }))
      .toEqual({ watermark: 'true', watermarkText: '出差留痕' });
    expect(uploadExtraFields({ id: 'photo', type: 'image_upload', label: '照片', props: { watermark: true } }))
      .toEqual({ watermark: 'true', watermarkText: 'AntFlow' });
  });

  it('image field opens the camera when source is camera', () => {
    render(<ImageUploadField {...baseProps({ id: 'photo', type: 'image_upload', label: '照片', props: { source: 'camera' } }, [])} />);
    expect(screen.getByLabelText('照片').getAttribute('capture')).toBe('environment');
    expect(screen.getByText('添加图片')).toBeInTheDocument();
  });

  it('image field does not force the camera for album or both sources', () => {
    render(<ImageUploadField {...baseProps({ id: 'photo', type: 'image_upload', label: '照片', props: { source: 'album' } }, [])} />);
    expect(screen.getByLabelText('照片').getAttribute('capture')).toBeNull();
  });

  it('detects iPhone HEIC photos for client-side conversion', () => {
    expect(isHeicFile({ name: 'IMG_1234.heic', type: 'image/heic' } as File)).toBe(true);
    expect(isHeicFile({ name: 'IMG_1234.HEIF', type: '' } as File)).toBe(true);
    expect(isHeicFile({ name: 'photo.png', type: 'image/png' } as File)).toBe(false);
  });

  it('轮询窗口用完但服务端还在转码：条目留在"处理中"，重试变成"继续等待"且不再传第二份', async () => {
    const stillProcessing = {
      id: 'file-9', name: 'clip.mp4', contentUrl: '/api/mobile/files/file-9/content',
      contentType: 'video/mp4', size: 10, status: 'PROCESSING',
    } as MobileFileDto;
    files.upload.mockRejectedValueOnce(new FileStillProcessingError(stillProcessing));
    files.wait.mockResolvedValue({ ...stillProcessing, status: 'READY' });
    const onValueChange = vi.fn();
    const node: MobileSchemaNode = { id: 'clip', type: 'video_upload', label: '视频' };
    const { container } = render(
      <FileUploadField {...baseProps(node, [], onValueChange)} />,
    );
    const input = container.querySelector<HTMLInputElement>('input[type="file"]');
    fireEvent.change(input as HTMLInputElement, {
      target: { files: [new File(['x'], 'clip.mp4', { type: 'video/mp4' })] },
    });

    // 判失败就会让用户重传、多出一份视频（视频不走去重），所以这里只能出现"继续等待"。
    expect(await screen.findByRole('button', { name: /继续等待/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^重试/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /继续等待/ }));

    await waitFor(() => expect(files.wait).toHaveBeenCalledWith(stillProcessing, expect.anything()));
    expect(files.upload).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(onValueChange).toHaveBeenCalledWith('clip',
      expect.arrayContaining([expect.objectContaining({ id: 'file-9' })])));
  });

  it('video field opens the camera when source is camera', () => {
    render(<VideoUploadField {...baseProps({ id: 'clip', type: 'video_upload', label: '视频', props: { source: 'camera' } }, [])} />);
    expect(screen.getByLabelText('视频').getAttribute('capture')).toBe('environment');
    expect(screen.getByText('添加视频')).toBeInTheDocument();
  });
});
