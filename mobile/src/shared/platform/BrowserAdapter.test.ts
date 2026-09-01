import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { browserAdapter } from './BrowserAdapter';
import type { MobileFile } from '../api/types';

const scanner = vi.hoisted(() => ({
  callback: undefined as undefined | ((result?: { getText(): string }) => void),
  stop: vi.fn(),
  trackStop: vi.fn(),
}));

vi.mock('@zxing/browser', () => ({
  BrowserMultiFormatReader: class {
    decodeFromConstraints(
      _constraints: MediaStreamConstraints,
      video: HTMLVideoElement,
      callback: (result?: { getText(): string }) => void,
    ) {
      scanner.callback = callback;
      Object.defineProperty(video, 'srcObject', {
        configurable: true,
        value: new MediaStream(),
      });
      return Promise.resolve({ stop: scanner.stop });
    }
  },
}));

const FILE: MobileFile = {
  id: 'file-1',
  name: 'file.pdf',
  contentUrl: '/api/files/file-1?signed=1',
  contentType: 'application/pdf',
  size: 1024,
};

describe('browserAdapter', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    scanner.callback = undefined;
    scanner.stop.mockReset();
    scanner.trackStop.mockReset();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('returns null for silent login in the browser', async () => {
    await expect(browserAdapter.trySilentLogin()).resolves.toBeNull();
  });

  it('opens same-origin signed file URLs with noopener and noreferrer', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);

    await browserAdapter.openFile(FILE);

    expect(open).toHaveBeenCalledWith(
      'http://localhost:3000/api/files/file-1?signed=1',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('rejects cross-origin file URLs', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);

    await expect(
      browserAdapter.openFile({ ...FILE, contentUrl: 'https://files.example.com/file-1?signed=1' }),
    ).rejects.toThrow('Only same-origin files can be opened');
    expect(open).not.toHaveBeenCalled();
  });

  it('opens legacy file URLs restored from local drafts', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);

    await browserAdapter.openFile({
      ...FILE,
      contentUrl: '',
      url: '/api/files/legacy-file?signed=1',
      sizeBytes: 1024,
    });

    expect(open).toHaveBeenCalledWith(
      'http://localhost:3000/api/files/legacy-file?signed=1',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('goes back when browser history has previous entries', () => {
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    vi.spyOn(window.history, 'length', 'get').mockReturnValue(2);

    browserAdapter.closePage();

    expect(back).toHaveBeenCalledTimes(1);
  });

  it('falls back to /mobile/ when history cannot go back', () => {
    const assign = vi.fn();
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    vi.spyOn(window.history, 'length', 'get').mockReturnValue(1);
    vi.spyOn(window.location, 'assign').mockImplementation(assign);

    browserAdapter.closePage();

    expect(back).not.toHaveBeenCalled();
    expect(assign).toHaveBeenCalledWith('/mobile/');
  });

  it('reports browser environment details', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: true,
      media: '(display-mode: standalone)',
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    });

    const environment = browserAdapter.getEnvironment();

    expect(environment.standalone).toBe(true);
    expect(environment.userAgent).toBe(navigator.userAgent);
  });

  it('returns WGS84 coordinates and accuracy from browser geolocation', async () => {
    const getCurrentPosition = vi.fn((success: PositionCallback) => success({
      coords: { latitude: 31.2, longitude: 121.5, accuracy: 8 },
    } as GeolocationPosition));
    vi.stubGlobal('navigator', { ...navigator, geolocation: { getCurrentPosition }, mediaDevices: navigator.mediaDevices });

    await expect(browserAdapter.getLocation?.()).resolves.toEqual({
      latitude: 31.2,
      longitude: 121.5,
      accuracy: 8,
      coordinateSystem: 'WGS84',
    });
  });

  it('scans in a full-screen camera and releases tracks after success or cancel', async () => {
    class FakeMediaStream {
      getTracks() { return [{ stop: scanner.trackStop }]; }
    }
    vi.stubGlobal('MediaStream', FakeMediaStream);
    vi.stubGlobal('navigator', {
      userAgent: navigator.userAgent,
      mediaDevices: { getUserMedia: vi.fn() },
      geolocation: navigator.geolocation,
    });
    const first = browserAdapter.scanCode?.();
    await vi.waitFor(() => expect(scanner.callback).toBeTypeOf('function'));
    const firstCallback = scanner.callback;
    scanner.callback?.({ getText: () => 'EAN-6901234567892' });
    await expect(first).resolves.toBe('EAN-6901234567892');
    expect(scanner.stop).toHaveBeenCalledTimes(1);
    expect(scanner.trackStop).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[aria-label="扫码取景器"]')).toBeNull();

    const second = browserAdapter.scanCode?.();
    await vi.waitFor(() => expect(scanner.callback).not.toBe(firstCallback));
    (document.querySelector('[aria-label="扫码取景器"] button') as HTMLButtonElement).click();
    await expect(second).resolves.toBeNull();
    expect(scanner.trackStop).toHaveBeenCalledTimes(2);
  });
});
