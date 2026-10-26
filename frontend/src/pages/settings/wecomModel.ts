import type { Dayjs } from 'dayjs';

export type SyncMode = 'FULL' | 'INCREMENTAL';
export type SyncStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
export type SyncJob = {
  id: number;
  status: SyncStatus;
  phase: string;
  percent: number;
  totalUsers: number;
  processedUsers: number;
  createdUsers: number;
  updatedUsers: number;
  failedUsers: number;
  message?: string;
  errorSummary: string[];
  startedAt?: string;
  finishedAt?: string;
  syncMode?: SyncMode;
};
export type Settings = {
  companyId: number;
  corpId: string;
  secretConfigured: boolean;
  latestJob?: SyncJob;
  agentId?: number;
  agentSecretConfigured: boolean;
  oauthEnabled: boolean;
  jsSdkEnabled: boolean;
  messageEnabled: boolean;
  scheduleEnabled: boolean;
  scheduleTime: string;
  scheduleMode: SyncMode;
};
export type FormValues = {
  corpId: string;
  secret?: string;
  agentId?: number;
  agentSecret?: string;
  oauthEnabled: boolean;
  jsSdkEnabled: boolean;
  messageEnabled: boolean;
  scheduleEnabled: boolean;
  scheduleTime: Dayjs;
  scheduleMode: SyncMode;
};
export type DeliveryStatus = { pending: number; dead: number; oldestPendingAt?: string };
export type AccessInfo = {
  publicBaseUrl: string;
  trustedDomain: string;
  mobileHomeUrl: string;
  oauthCallbackUrl: string;
  verificationFileUrl: string;
  secure: boolean;
};

export const WECOM_GREEN = '#07c160';

/** 只有"等待执行/同步中"才算活跃——决定是否继续轮询、是否禁用启动按钮。 */
export const active = (job?: SyncJob) =>
  job?.status === 'PENDING' || job?.status === 'RUNNING';

export const syncPollInterval = (job?: SyncJob) => (active(job) ? 1000 : false);

export const phaseLabels: Record<string, string> = {
  CONNECTING: '连接企微',
  DEPARTMENTS: '部门同步',
  FETCHING_USERS: '获取成员',
  USERS: '用户同步',
  RELATIONS: '关系同步',
  COMPLETED: '完成',
};

export const statusMeta: Record<SyncStatus, { label: string; color: string }> = {
  PENDING: { label: '等待执行', color: 'default' },
  RUNNING: { label: '同步中', color: 'processing' },
  SUCCESS: { label: '同步成功', color: 'success' },
  PARTIAL: { label: '部分成功', color: 'warning' },
  FAILED: { label: '同步失败', color: 'error' },
};
