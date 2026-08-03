import type { PropsWithChildren } from 'react';
import { createContext, useContext } from 'react';
import { browserAdapter } from './BrowserAdapter';
import type { PlatformAdapter } from './PlatformAdapter';

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
