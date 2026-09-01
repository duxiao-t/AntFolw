import type { PropsWithChildren } from 'react';
import { createContext, useContext } from 'react';
import { browserAdapter } from './BrowserAdapter';
import { wecomAdapter } from './WecomAdapter';
import type { PlatformAdapter } from './PlatformAdapter';

export function defaultPlatformAdapter(): PlatformAdapter {
  if (typeof navigator !== 'undefined' && /wxwork/i.test(navigator.userAgent)) return wecomAdapter;
  return browserAdapter;
}

const PlatformContext = createContext<PlatformAdapter>(browserAdapter);

export function PlatformProvider({
  children,
  adapter = defaultPlatformAdapter(),
}: PropsWithChildren<{ adapter?: PlatformAdapter }>) {
  return <PlatformContext.Provider value={adapter}>{children}</PlatformContext.Provider>;
}

export function usePlatformAdapter(): PlatformAdapter {
  return useContext(PlatformContext);
}
