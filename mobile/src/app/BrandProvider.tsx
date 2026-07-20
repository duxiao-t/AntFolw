import type { PropsWithChildren } from 'react';
import { createContext, useContext } from 'react';

export interface PublicBranding {
  version: string;
  appName: string;
  companyName: string;
  primaryColor: string;
  mobileHeaderTitle: string;
  loginTitle: string;
  showLoginFooter: boolean;
  footerText: string;
}

const fallbackBranding: PublicBranding = {
  version: 'builtin-1',
  appName: 'AntFlow 审批',
  companyName: 'AntFlow',
  primaryColor: '#1677ff',
  mobileHeaderTitle: '工作台',
  loginTitle: '登录 AntFlow',
  showLoginFooter: true,
  footerText: '© 2026 AntFlow',
};

const BrandContext = createContext<PublicBranding>(fallbackBranding);

export function BrandProvider({ children }: PropsWithChildren) {
  return <BrandContext.Provider value={fallbackBranding}>{children}</BrandContext.Provider>;
}

export function useBranding(): PublicBranding {
  return useContext(BrandContext);
}
