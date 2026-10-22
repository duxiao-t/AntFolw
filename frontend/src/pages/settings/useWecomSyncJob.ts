import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { App } from 'antd';
import { useState } from 'react';
import { active, syncPollInterval } from './wecomModel';
import type { Settings, SyncJob, SyncMode } from './wecomModel';

type Params = { companyId?: number; settings?: Settings };

/**
 * 当前同步任务：查询（含轮询）、启动写操作、手工同步模式。
 * `startedJobId` 必须与 `companyId` 同步重置，所以页面在切公司时调用 `resetStartedJob`。
 */
export function useWecomSyncJob({ companyId, settings }: Params) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [startedJobId, setStartedJobId] = useState<number>();
  const [syncMode, setSyncMode] = useState<SyncMode>('INCREMENTAL');

  const jobId = startedJobId ?? settings?.latestJob?.id;
  const jobQuery = useQuery({
    queryKey: ['wecom-sync-job', jobId],
    queryFn: () => request<SyncJob>(`/api/integrations/wecom/sync-jobs/${jobId}`),
    enabled: jobId !== undefined && (startedJobId !== undefined || active(settings?.latestJob)),
    initialData: jobId === settings?.latestJob?.id ? settings?.latestJob : undefined,
    refetchInterval: (query) => syncPollInterval(query.state.data as SyncJob | undefined),
  });
  const job = (jobQuery.data ?? settings?.latestJob) as SyncJob | undefined;

  const startMutation = useMutation<SyncJob, Error, SyncMode>({
    mutationFn: (mode: SyncMode) => request<SyncJob>('/api/integrations/wecom/sync-jobs', {
      method: 'POST',
      data: { companyId, mode },
    }),
    onSuccess: (started) => {
      setStartedJobId(started.id);
      queryClient.setQueryData(['wecom-sync-job', started.id], started);
      message.success(started.status === 'PENDING' ? '同步任务已创建' : '已恢复正在运行的任务');
    },
  });

  return {
    job,
    syncMode,
    setSyncMode,
    startMutation,
    resetStartedJob: () => setStartedJobId(undefined),
  };
}
