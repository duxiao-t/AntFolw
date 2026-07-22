import type { AppFilters } from './types';
import type { TaskCenterFilters } from '../../features/tasks/tasks.api';

export const queryKeys = {
  branding: ['branding'] as const,
  bootstrap: ['mobile', 'bootstrap'] as const,
  apps: (filters: AppFilters) => ['mobile', 'apps', filters] as const,
  form: (code: string) => ['mobile', 'forms', code] as const,
  drafts: ['mobile', 'drafts'] as const,
  draft: (id: number) => ['mobile', 'drafts', id] as const,
  instance: (id: number) => ['mobile', 'instances', id] as const,
  tasks: (filters: TaskCenterFilters) => ['mobile', 'tasks', filters] as const,
  sessions: ['auth', 'sessions'] as const,
} as const;
