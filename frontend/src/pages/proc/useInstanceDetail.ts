import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { FieldMode, SchemaNode } from '../../registry/types';
import { buildFieldModes, parseJsonValue } from './detailModel';
import {
  buildActivityItems,
  buildProgressStages,
  type HistoryView,
  type InstancePresentation,
  parseProcessSnapshot,
  type PersonView,
} from './detailPresentation';

/**
 * 详情页唯一的服务端数据入口：实例详情 + 历史操作人。
 * 头部、进度、表单、自动化、轨迹、弹窗全部吃这一份，避免同一实例被拉多次。
 */
export function useInstanceDetail(id?: string, currentUserId?: number) {
  const detailQuery = useQuery<{
    runtime: Record<string, any>;
    presentation: InstancePresentation;
  }>({
    queryKey: ['instance', id],
    queryFn: async () => {
      const [runtime, presentation] = await Promise.all([
        request<Record<string, any>>(`/api/instances/${id}`),
        request<InstancePresentation>(`/api/mobile/instances/${id}`),
      ]);
      return { runtime, presentation };
    },
  });
  const data = detailQuery.data?.runtime;
  const presentation = detailQuery.data?.presentation;
  const instance = (data as any)?.instance;
  const tasks: any[] = (data as any)?.tasks ?? [];

  const [editableValues, setEditableValues] = useState<Record<string, any>>({});

  const snapshotObj = useMemo(
    () => parseProcessSnapshot((data as any)?.instance?.processSnapshot),
    [data],
  );
  const formSchema = useMemo(
    () => parseJsonValue<SchemaNode[]>((data as any)?.schema, []),
    [data],
  );
  const initialFormData = useMemo(
    () => parseJsonValue<Record<string, any>>((data as any)?.formData, {}),
    [data],
  );

  const myPending = tasks.find(
    (task: any) =>
      task.status === 'PENDING' &&
      task.taskType !== 'REWORK' &&
      currentUserId != null &&
      task.assigneeId === currentUserId,
  );
  // 编辑框初值：(1) 换任务时一定重置；(2) 同一个任务下服务端数据变了、而用户还没动过手时跟随更新。
  // 原实现只依赖 `myPending?.id`，详情重新拉取（窗口焦点就会触发）后 effect 不跑，editableValues
  // 一直留着旧值 —— 用户拿着陈旧数据点"同意"，会把旧值当编辑结果提交回去。
  // 用户已经改过就不动它，避免把人正在填的内容冲掉。
  const editableBaselineRef = useRef<{ taskId?: number; data: Record<string, any> }>({ data: {} });
  useEffect(() => {
    if (!myPending) return;
    const sameTask = editableBaselineRef.current.taskId === myPending.id;
    const untouched =
      JSON.stringify(editableValues) === JSON.stringify(editableBaselineRef.current.data);
    if (!sameTask || untouched) {
      setEditableValues(initialFormData);
      editableBaselineRef.current = { taskId: myPending.id, data: initialFormData };
    }
  }, [myPending?.id, initialFormData, editableValues]);

  const currentFormModes = useMemo<Record<string, FieldMode>>(
    () => buildFieldModes({
      snapshot: snapshotObj,
      pendingNodeId: myPending?.nodeId,
      currentUserId,
      startedBy: instance?.startedBy,
    }),
    [snapshotObj, myPending, currentUserId, instance?.startedBy],
  );
  const hasEditableFields = Object.values(currentFormModes).includes('runtime-fill');

  const historyRows: HistoryView[] = presentation?.history ?? (data as any)?.history ?? [];
  const fullVisibility = data?.visibility !== 'SUMMARY';
  const actorIds = useMemo(
    () => fullVisibility
      ? [...new Set(historyRows.flatMap((row) => row.operatorId == null ? [] : [row.operatorId]))]
          .sort((left, right) => left - right)
      : [],
    [fullVisibility, historyRows],
  );
  const actorsQuery = useQuery<Record<number, PersonView>>({
    queryKey: ['instance-actors', id, actorIds.join(',')],
    queryFn: async () => {
      if (actorIds.length === 0) return {};
      // 一次批量取人：原来按 history 里的每个 operatorId 各发一条
      // /api/pickers/users/{id}，20 条历史就是 20 个并发请求（见 code-review C5）。
      // 后端已有批量入口（单条 SQL + SELECTED_LIMIT 防翻目录），这里改成一发一收。
      const placeholders = () =>
        Object.fromEntries(actorIds.map((actorId) => [actorId, { id: actorId }]));
      try {
        const people = await request<PersonView[]>('/api/pickers/users/selected', {
          method: 'GET',
          params: { ids: actorIds.join(',') },
          skipErrorHandler: true,
        });
        const byId = new Map((people ?? []).map((person) => [person.id, person]));
        // 账号被删或不在可见范围时补占位，避免整块历史因为缺一个人而空白
        return Object.fromEntries(
          actorIds.map((actorId) => [actorId, byId.get(actorId) ?? { id: actorId }]),
        );
      } catch {
        return placeholders();
      }
    },
    enabled: actorIds.length > 0,
    retry: 0,
  });

  const progressStages = useMemo(
    () => buildProgressStages({
      root: snapshotObj,
      nodeInstances: (data as any)?.nodeInstances ?? [],
      approvalRecords: presentation?.approvalRecords ?? [],
      instanceStatus: instance?.status ?? '',
      currentNodeId: instance?.currentNodeId,
      currentNodeName: presentation?.currentNodeName,
    }),
    [data, instance?.currentNodeId, instance?.status, presentation?.approvalRecords,
      presentation?.currentNodeName, snapshotObj],
  );
  const activityItems = useMemo(
    () => buildActivityItems({
      root: snapshotObj,
      approvalRecords: presentation?.approvalRecords ?? [],
      history: historyRows,
      people: actorsQuery.data,
      applicantName: presentation?.applicantName,
    }),
    [actorsQuery.data, historyRows, presentation?.applicantName,
      presentation?.approvalRecords, snapshotObj],
  );

  return {
    detailQuery,
    data,
    presentation,
    instance,
    tasks,
    snapshotObj,
    formSchema,
    initialFormData,
    editableValues,
    setEditableValues,
    myPending,
    currentFormModes,
    hasEditableFields,
    fullVisibility,
    progressStages,
    activityItems,
  };
}

export type InstanceDetail = ReturnType<typeof useInstanceDetail>;
