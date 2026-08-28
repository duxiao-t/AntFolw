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

export interface MobileUser {
  id: number;
  username: string;
  displayName: string;
  department?: string;
  avatarUrl?: string;
  roles: string[];
}

export interface DeviceSession {
  id: string;
  deviceName: string;
  platform: 'browser' | 'wecom';
  lastActiveAt: string;
  isCurrent: boolean;
}

export interface MobileApp {
  formId: number;
  code: string;
  name: string;
  iconUrl?: string;
  category?: string;
  categoryLabel?: string;
  description?: string;
}

export interface RecentProcess {
  instanceId: number;
  formCode: string;
  formTitle: string;
  status: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  startedAt?: string;
  updatedAt: string;
}

export interface MobileBootstrap {
  user: MobileUser;
  pendingCount: number;
  favoriteApps: MobileApp[];
  recentProcesses: RecentProcess[];
  brandingVersion: string;
}

export interface AppFilters {
  keyword?: string;
  category?: string;
}

export interface MobileFile {
  id: string;
  name: string;
  contentUrl: string;
  contentType: string;
  size: number;
  url?: string;
  sizeBytes?: number;
  status?: 'PROCESSING' | 'READY' | 'FAILED';
}
