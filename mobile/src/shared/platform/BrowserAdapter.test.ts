import { beforeEach, describe, expect, it, vi } from 'vitest';
import { browserAdapter } from './BrowserAdapter';
import type { MobileFile } from '../api/types';

const FILE: MobileFile = {
  id: 'file-1',
  url: '/api/files/file-1?signed=1',
  contentType: 'application/pdf',
  sizeBytes: 1024,
};

describe('browserAdapter', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

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
      browserAdapter.openFile({ ...FILE, url: 'https://files.example.com/file-1?signed=1' }),
    ).rejects.toThrow('Only same-origin files can be opened');
    expect(open).not.toHaveBeenCalled();
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
});
