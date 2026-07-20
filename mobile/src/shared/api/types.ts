import type { PublicBranding } from '../../app/BrandProvider';

export interface MobileUser {
  id: number;
  username: string;
  displayName: string;
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
  id: number;
  code: string;
  name: string;
  iconUrl?: string;
  category: string;
  description?: string;
}

export interface RecentProcess {
  instanceId: number;
  formCode: string;
  formTitle: string;
  status: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
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
  url: string;
  contentType: string;
  sizeBytes: number;
}

export type { PublicBranding };
