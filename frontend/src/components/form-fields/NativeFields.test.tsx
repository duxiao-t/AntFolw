import { App } from 'antd';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentType } from 'react';
import type { FieldComponentProps, SchemaNode } from '../../registry/types';
import { AudioUploadField } from './AudioUploadField';
import { FileUploadField } from './FileUploadField';
import { LocationField } from './LocationField';
import { ScanCodeField } from './ScanCodeField';

const media = vi.hoisted(() => ({
  scan: vi.fn(), begin: vi.fn(), upload: vi.fn(), location: vi.fn(), open: vi.fn(),
}));

vi.mock('./nativeMedia', () => ({
  scanCodeWithCamera: media.scan,
  beginAudioRecording: media.begin,
  uploadNativeFile: media.upload,
  getBrowserLocation: media.location,
  openNativeLocation: media.open,
}));

const file = {
  id: 'voice-1', name: 'voice.webm', contentUrl: '/files/voice-1',
  contentType: 'audio/webm', size: 12, durationSeconds: 8,
};

describe('desktop native fields', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the shared designer display frame for native output fields', () => {
    render(
      <App>
        <AudioUploadField.Component node={{ id: 'voice', type: 'audio_upload', label: '录音' }} mode="designer-preview" />
        <LocationField.Component node={{ id: 'place', type: 'location', label: '定位' }} mode="designer-preview" />
        <FileUploadField.Component node={{ id: 'file', type: 'file_upload', label: '文件' }} mode="designer-preview" />
      </App>,
    );

    expect(document.querySelectorAll('.form-fields-media-placeholder')).toHaveLength(3);
  });

  it('writes a successful camera scan while keeping manual entry available', async () => {
    media.scan.mockResolvedValue('CODE-128');
    const onChange = vi.fn();
    renderField(ScanCodeField.Component, { id: 'code', type: 'scan_code', label: '条码' }, '', onChange);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'MANUAL' } });
    fireEvent.click(screen.getByRole('button', { name: /扫码/ }));
    await act(async () => { await Promise.resolve(); });
    expect(onChange).toHaveBeenCalledWith('MANUAL');
    expect(onChange).toHaveBeenLastCalledWith('CODE-128');
  });

  it('records and uploads audio without choosing an existing file', async () => {
    const stop = vi.fn().mockResolvedValue({ file: new File(['voice'], 'voice.webm', { type: 'audio/webm' }), durationSeconds: 8 });
    media.begin.mockResolvedValue({ stop });
    media.upload.mockResolvedValue(file);
    const onChange = vi.fn();
    renderField(AudioUploadField.Component, { id: 'voice', type: 'audio_upload', label: '录音', props: { maxCount: 3, maxDuration: 60 } }, [], onChange);
    fireEvent.click(screen.getByRole('button', { name: /开始录音/ }));
    await act(async () => { await Promise.resolve(); });
    fireEvent.click(screen.getByRole('button', { name: /停止录音/ }));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(stop).toHaveBeenCalledTimes(1);
    expect(media.upload).toHaveBeenCalled();
    expect(onChange).toHaveBeenLastCalledWith([file]);
  });

  it('gets browser coordinates and supports map and clear actions', async () => {
    const location = { latitude: 31.2, longitude: 121.5, accuracy: 8, coordinateSystem: 'WGS84' };
    media.location.mockResolvedValue(location);
    const onChange = vi.fn();
    const view = renderField(LocationField.Component, { id: 'place', type: 'location', label: '位置' }, null, onChange);
    fireEvent.click(screen.getByRole('button', { name: /获取当前位置/ }));
    await act(async () => { await Promise.resolve(); });
    expect(onChange).toHaveBeenCalledWith(location);

    view.rerender(<App><LocationField.Component node={{ id: 'place', type: 'location', label: '位置' }} mode="runtime-fill" value={location} onChange={onChange} /></App>);
    fireEvent.click(screen.getByRole('button', { name: /在地图中打开/ }));
    fireEvent.click(screen.getByRole('button', { name: /清除/ }));
    expect(media.open).toHaveBeenCalledWith(location);
    expect(onChange).toHaveBeenLastCalledWith(undefined);
  });
});

function renderField(Component: ComponentType<FieldComponentProps>, node: SchemaNode, value: unknown, onChange: (value: unknown) => void) {
  return render(<App><Component node={node} mode="runtime-fill" value={value} onChange={onChange} /></App>);
}
