import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { maxCountError, uploadExtraFields, videoDurationError } from './FileUploadField';
import { ImageUploadField } from './ImageUploadField';
import { VideoUploadField } from './VideoUploadField';

function baseProps(node: MobileSchemaNode, value: unknown, onValueChange = vi.fn()) {
  return { node, value, values: { [node.id]: value }, mode: 'fill' as const, error: undefined, onValueChange };
}

describe('media upload fields', () => {
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

  it('video field opens the camera when source is camera', () => {
    render(<VideoUploadField {...baseProps({ id: 'clip', type: 'video_upload', label: '视频', props: { source: 'camera' } }, [])} />);
    expect(screen.getByLabelText('视频').getAttribute('capture')).toBe('environment');
    expect(screen.getByText('添加视频')).toBeInTheDocument();
  });
});
