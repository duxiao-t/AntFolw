import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PlatformAdapter } from '../../../shared/platform/PlatformAdapter';
import { PlatformProvider } from '../../../shared/platform/PlatformProvider';
import { AudioUploadField } from './AudioUploadField';
import { LocationField } from './LocationField';
import { ScanCodeField } from './ScanCodeField';
import type { MobileFieldProps, MobileSchemaNode } from '../schema/types';

const AUDIO_FILE = {
  id: 'voice-1', name: 'voice.amr', contentUrl: '/files/voice-1',
  contentType: 'audio/amr', size: 12, durationSeconds: 5,
};

describe('native form fields', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('auto-stops recording at the configured duration and stores the uploaded clip', async () => {
    vi.useFakeTimers();
    const adapter = platform({
      startAudioRecording: vi.fn().mockResolvedValue(undefined),
      stopAudioRecording: vi.fn().mockResolvedValue({ uploaded: AUDIO_FILE, durationSeconds: 5 }),
    });
    const onValueChange = vi.fn();
    renderField(<AudioUploadField {...props({
      id: 'voice', type: 'audio_upload', label: '现场录音', props: { maxCount: 3, maxDuration: 5 },
    }, [], onValueChange)} />, adapter);

    fireEvent.click(screen.getByRole('button', { name: /开始录音/ }));
    await act(async () => { await Promise.resolve(); });
    await act(async () => { vi.advanceTimersByTime(5_000); await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });

    expect(adapter.stopAudioRecording).toHaveBeenCalledTimes(1);
    expect(onValueChange).toHaveBeenLastCalledWith('voice', [AUDIO_FILE]);
  });

  it('keeps manual scan input and treats scanner cancellation as no change', async () => {
    const adapter = platform({ scanCode: vi.fn().mockResolvedValue(null) });
    const onValueChange = vi.fn();
    renderField(<ScanCodeField {...props({ id: 'code', type: 'scan_code', label: '物料码' }, 'A-1', onValueChange)} />, adapter);

    fireEvent.change(screen.getByLabelText('物料码'), { target: { value: 'A-2' } });
    fireEvent.click(screen.getByRole('button', { name: '扫码' }));
    await act(async () => { await Promise.resolve(); });

    expect(onValueChange).toHaveBeenCalledWith('code', 'A-2');
    expect(onValueChange).toHaveBeenCalledTimes(1);
  });

  it('gets, opens and clears native coordinates', async () => {
    const location = { latitude: 31.2, longitude: 121.5, accuracy: 8, coordinateSystem: 'WGS84' as const };
    const adapter = platform({
      getLocation: vi.fn().mockResolvedValue(location),
      openLocation: vi.fn().mockResolvedValue(undefined),
    });
    const onValueChange = vi.fn();
    const view = renderField(<LocationField {...props({ id: 'place', type: 'location', label: '位置' }, null, onValueChange)} />, adapter);
    fireEvent.click(screen.getByRole('button', { name: /获取当前位置/ }));
    await act(async () => { await Promise.resolve(); });
    expect(onValueChange).toHaveBeenCalledWith('place', location);

    view.rerender(<PlatformProvider adapter={adapter}><LocationField {...props({ id: 'place', type: 'location', label: '位置' }, location, onValueChange)} /></PlatformProvider>);
    fireEvent.click(screen.getByRole('button', { name: '在地图中打开' }));
    fireEvent.click(screen.getByRole('button', { name: '清除位置' }));
    expect(adapter.openLocation).toHaveBeenCalledWith(location);
    expect(onValueChange).toHaveBeenLastCalledWith('place', undefined);
  });
});

function platform(overrides: Partial<PlatformAdapter>): PlatformAdapter {
  return {
    kind: 'browser', trySilentLogin: async () => null, openFile: async () => undefined,
    closePage: () => undefined, getEnvironment: () => ({ standalone: false, userAgent: '' }),
    ...overrides,
  };
}

function props(node: MobileSchemaNode, value: unknown, onValueChange: MobileFieldProps['onValueChange']): MobileFieldProps {
  return { node, value, values: {}, mode: 'fill', onValueChange };
}

function renderField(field: React.ReactNode, adapter: PlatformAdapter) {
  return render(<PlatformProvider adapter={adapter}>{field}</PlatformProvider>);
}
