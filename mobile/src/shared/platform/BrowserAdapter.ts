import type { MobileFile } from '../api/types';
import type { PlatformAdapter, PlatformEnvironment } from './PlatformAdapter';

function resolveSameOriginUrl(url: string): string {
  const resolved = new URL(url, window.location.origin);
  if (resolved.origin !== window.location.origin) {
    throw new Error('Only same-origin files can be opened');
  }
  return resolved.toString();
}

export const browserAdapter: PlatformAdapter = {
  kind: 'browser',
  async trySilentLogin() {
    return null;
  },
  async openFile(file: MobileFile) {
    if (typeof window === 'undefined') return;
    const url = resolveSameOriginUrl(file.url);
    window.open(url, '_blank', 'noopener,noreferrer');
  },
  closePage() {
    if (typeof window === 'undefined') return;
    if (window.history.length > 1) {
      window.history.back();
      return;
    }
    window.location.assign('/mobile/');
  },
  getEnvironment(): PlatformEnvironment {
    return {
      standalone:
        typeof window !== 'undefined' &&
        Boolean(window.matchMedia?.('(display-mode: standalone)').matches),
      userAgent: typeof navigator === 'undefined' ? '' : navigator.userAgent,
    };
  },
};
