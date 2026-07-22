import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from 'react';
import { OfflineBanner } from '../ui/PageStates';

export type NetworkStatusValue = {
  online: boolean;
  /** False when offline — disable new writes; keep local form input. */
  canWrite: boolean;
};

const NetworkStatusContext = createContext<NetworkStatusValue>({
  online: true,
  canWrite: true,
});

function readOnline(): boolean {
  if (typeof navigator === 'undefined') {
    return true;
  }
  return navigator.onLine;
}

export function NetworkStatusProvider({ children }: PropsWithChildren) {
  const [online, setOnline] = useState(readOnline);

  useEffect(() => {
    const handleOnline = () => setOnline(true);
    const handleOffline = () => setOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    setOnline(readOnline());
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  const onRetry = useCallback(() => {
    setOnline(readOnline());
  }, []);

  const value = useMemo<NetworkStatusValue>(
    () => ({ online, canWrite: online }),
    [online],
  );

  return (
    <NetworkStatusContext.Provider value={value}>
      {!online ? (
        <div
          data-testid="offline-banner-host"
          style={{ position: 'sticky', top: 0, zIndex: 1000 }}
        >
          <OfflineBanner onRetry={onRetry} />
        </div>
      ) : null}
      {children}
    </NetworkStatusContext.Provider>
  );
}

export function useNetworkStatus(): NetworkStatusValue {
  return useContext(NetworkStatusContext);
}

export default NetworkStatusProvider;
