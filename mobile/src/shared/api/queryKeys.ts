import type { AppFilters } from './types';

export const queryKeys = {
  branding: ['branding'] as const,
  bootstrap: ['mobile', 'bootstrap'] as const,
  apps: (filters: AppFilters) => ['mobile', 'apps', filters] as const,
  sessions: ['auth', 'sessions'] as const,
} as const;
