import type { PropsWithChildren } from 'react';
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../../shared/api/queryKeys';
import { fetchPublicBranding } from './branding.api';
import { applyBrandTokens, FALLBACK_BRANDING } from './brandTokens';
import type { PublicBranding } from '../../shared/api/types';

const BrandContext = createContext<PublicBranding>(FALLBACK_BRANDING);

export interface BrandProviderProps extends PropsWithChildren {
  initial?: PublicBranding;
}

export function BrandProvider({ children, initial }: BrandProviderProps) {
  const [branding, setBranding] = useState<PublicBranding>(initial ?? FALLBACK_BRANDING);

  const query = useQuery({
    queryKey: queryKeys.branding,
    queryFn: fetchPublicBranding,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
    retry: 0,
  });

  const next = useMemo(() => {
    if (query.data) return query.data;
    return branding;
  }, [query.data, branding]);

  useEffect(() => {
    applyBrandTokens(next);
    setBranding(next);
  }, [next]);

  return <BrandContext.Provider value={next}>{children}</BrandContext.Provider>;
}

export function useBranding(): PublicBranding {
  return useContext(BrandContext);
}

export default BrandProvider;
