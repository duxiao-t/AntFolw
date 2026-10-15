import type { AntFlowAPI } from '../../../../shared/api.generated';

export type Contract<T, K extends keyof T> = T & Required<Pick<T, K>>;

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

export type MobileUser = Contract<
  AntFlowAPI.MobileUserDto,
  'id' | 'username' | 'displayName' | 'roles'
> & { avatarUrl?: string };

export type DeviceSession = Contract<
  AntFlowAPI.DeviceSessionDto,
  'id' | 'deviceName' | 'platform' | 'lastActiveAt' | 'isCurrent'
>;

export type MobileApp = Contract<
  AntFlowAPI.MobileAppDto,
  'formId' | 'code' | 'name'
>;

export type RecentProcess = Contract<
  AntFlowAPI.RecentProcessDto,
  'instanceId' | 'formCode' | 'formTitle' | 'status' | 'updatedAt'
> & { startedAt?: string };

export type MobileBootstrap = Contract<
  Omit<
    AntFlowAPI.MobileBootstrapDto,
    'user' | 'favoriteApps' | 'recentProcesses'
  >,
  'pendingCount' | 'brandingVersion'
> & {
  user: MobileUser;
  favoriteApps: MobileApp[];
  recentProcesses: RecentProcess[];
};

export interface AppFilters {
  keyword?: string;
  category?: string;
}

export type MobileFile = Contract<
  AntFlowAPI.MobileFileDto,
  'id' | 'name' | 'contentUrl' | 'contentType' | 'size'
> & { url?: string; sizeBytes?: number };
