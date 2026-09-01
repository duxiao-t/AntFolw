import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

function json(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify(data), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  }));
}

function wxMock() {
  let ready: () => void = () => undefined;
  type Options = { success(result?: Record<string, unknown>): void };
  return {
    config: vi.fn(() => ready()),
    ready: vi.fn((callback: () => void) => { ready = callback; }),
    error: vi.fn(),
    chooseImage: vi.fn((options: Options) => options.success({ localIds: ['local-image'] })),
    uploadImage: vi.fn((options: Options) => options.success({ serverId: 'server-image' })),
    startRecord: vi.fn((options: Options) => options.success()),
    stopRecord: vi.fn((options: Options) => options.success({ localId: 'local-voice' })),
    onVoiceRecordEnd: vi.fn(),
    uploadVoice: vi.fn((options: Options) => options.success({ serverId: 'server-voice' })),
    scanQRCode: vi.fn((options: Options) => options.success({ resultStr: 'CODE_128,12345' })),
    getLocation: vi.fn((options: Options) => options.success({ latitude: 31.2, longitude: 121.5, accuracy: 5 })),
    openLocation: vi.fn((options: Options) => options.success()),
    previewFile: vi.fn((options: Options) => options.success()),
    closeWindow: vi.fn(),
  };
}

describe('wecomAdapter', () => {
  beforeEach(() => {
    vi.resetModules();
    window.wx = wxMock() as unknown as Window['wx'];
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/js-sdk-config')) {
        return json({ appId: 'ww-corp', timestamp: 1, nonceStr: 'nonce', signature: 'signature' });
      }
      if (url.includes('/media/import')) {
        const mediaType = JSON.parse(String(init?.body)).mediaType;
        return json({ id: `${mediaType}-file`, name: `${mediaType}.bin`, contentUrl: '/file',
          contentType: mediaType === 'voice' ? 'audio/amr' : 'image/jpeg', size: 12 });
      }
      throw new Error(`unexpected request ${url}`);
    }));
  });

  afterEach(() => {
    delete window.wx;
    vi.unstubAllGlobals();
  });

  it('configures the SDK once and scans codes', async () => {
    const { wecomAdapter } = await import('./WecomAdapter');
    await expect(wecomAdapter.scanCode?.()).resolves.toBe('12345');
    await expect(wecomAdapter.getLocation?.()).resolves.toEqual({
      latitude: 31.2, longitude: 121.5, accuracy: 5, coordinateSystem: 'GCJ02',
    });
    expect(window.wx?.config).toHaveBeenCalledTimes(1);
  });

  it('imports selected images and recorded voice into local storage', async () => {
    const { wecomAdapter } = await import('./WecomAdapter');
    await expect(wecomAdapter.chooseImages?.(1)).resolves.toEqual([
      expect.objectContaining({ id: 'image-file', contentType: 'image/jpeg' }),
    ]);
    await wecomAdapter.startAudioRecording?.();
    await expect(wecomAdapter.stopAudioRecording?.()).resolves.toEqual(
      expect.objectContaining({
        uploaded: expect.objectContaining({ id: 'voice-file', contentType: 'audio/amr' }),
        durationSeconds: expect.any(Number),
      }),
    );
    const fetchMock = vi.mocked(fetch);
    const imports = fetchMock.mock.calls.filter(([url]) => String(url).includes('/media/import'));
    expect(imports).toHaveLength(2);
    expect(new Headers(imports[0]?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
  });

  it('uploads a voice that reaches the WeCom automatic limit exactly once', async () => {
    const { wecomAdapter } = await import('./WecomAdapter');
    await wecomAdapter.startAudioRecording?.();
    const sdk = window.wx;
    if (!sdk) throw new Error('wx mock unavailable');
    const endOptions = vi.mocked(sdk.onVoiceRecordEnd).mock.calls[0]?.[0] as {
      complete(result: Record<string, unknown>): void;
    };
    endOptions.complete({ localId: 'auto-ended-voice' });
    await vi.waitFor(() => expect(window.wx?.uploadVoice).toHaveBeenCalledTimes(1));

    await expect(wecomAdapter.stopAudioRecording?.()).resolves.toEqual(
      expect.objectContaining({ uploaded: expect.objectContaining({ id: 'voice-file' }) }),
    );
    expect(window.wx?.stopRecord).not.toHaveBeenCalled();
    expect(window.wx?.uploadVoice).toHaveBeenCalledTimes(1);
  });
});
