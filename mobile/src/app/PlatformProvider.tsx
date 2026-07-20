import type { PropsWithChildren } from 'react';
import { createContext, useContext } from 'react';

export type PlatformKind = 'browser' | 'wecom';

export interface PlatformEnvironment {
  standalone: boolean;
  userAgent: string;
}

export interface PlatformAdapter {
  readonly kind: PlatformKind;
  trySilentLogin(): Promise<null>;
  openFile(file: { url: string }): Promise<void>;
  closePage(): void;
  getEnvironment(): PlatformEnvironment;
}

const browserAdapter: PlatformAdapter = {
  kind: 'browser',
  async trySilentLogin() {
    return null;
  },
  async openFile(file) {
    if (typeof window === 'undefined') return;
    window.open(file.url, '_blank', 'noopener,noreferrer');
  },
  closePage() {
    if (typeof window === 'undefined') return;
    if (window.history.length > 1) {
      window.history.back();
    } else {
      window.location.assign('/mobile/');
    }
  },
  getEnvironment() {
    return {
      standalone:
        typeof window !== 'undefined' &&
        Boolean(window.matchMedia?.('(display-mode: standalone)').matches),
      userAgent: typeof navigator === 'undefined' ? '' : navigator.userAgent,
    };
  },
};

const PlatformContext = createContext<PlatformAdapter>(browserAdapter);

export function PlatformProvider({
  children,
  adapter = browserAdapter,
}: PropsWithChildren<{ adapter?: PlatformAdapter }>) {
  return <PlatformContext.Provider value={adapter}>{children}</PlatformContext.Provider>;
}

export function usePlatformAdapter(): PlatformAdapter {
  return useContext(PlatformContext);
}
