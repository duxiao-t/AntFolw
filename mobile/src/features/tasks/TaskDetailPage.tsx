import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from 'antd-mobile';
import type { CSSProperties } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { isApiError } from '../../shared/api/errors';
import { queryKeys } from '../../shared/api/queryKeys';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { DynamicFormRenderer } from '../forms/components/DynamicFormRenderer';
import type { MobileFormValues, MobileSchemaNode } from '../forms/schema/types';
import { ApproveSheet } from './ApproveSheet';
import { RejectSheet } from './RejectSheet';
import { TaskTimeline } from './TaskTimeline';
import {
  fetchTaskDetail,
  runTaskAction,
  type TaskActionPayload,
} from './tasks.api';

const sectionStyle: CSSProperties = {
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
  padding: 12,
  display: 'grid',
  gap: 10,
};

const labelStyle: CSSProperties = {
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

const metaStyle: CSSProperties = {
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

const bottomActionStyle: CSSProperties = {
  position: 'fixed',
  left: 0,
  right: 0,
  bottom: 0,
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 8,
  padding: '12px 16px calc(16px + env(safe-area-inset-bottom))',
  background: 'var(--af-color-bg)',
  boxShadow: '0 -8px 20px rgba(0,0,0,0.08)',
};

const noticeStyle: CSSProperties = {
  margin: 0,
  padding: '10px 12px',
  borderRadius: 8,
  background: 'rgba(22, 119, 255, 0.08)',
  color: 'var(--af-color-primary)',
};

export function TaskDetailPage() {
  const { taskId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericTaskId = Number(taskId);
  const [approveOpen, setApproveOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [actionError, setActionError] = useState('');
  const [statusNotice, setStatusNotice] = useState('');

  const detailQuery = useQuery({
    queryKey: queryKeys.taskDetail(numericTaskId),
    queryFn: () => fetchTaskDetail(numericTaskId),
    enabled: Number.isSafeInteger(numericTaskId) && numericTaskId > 0,
    retry: 0,
  });

  const actionMutation = useMutation({
    mutationFn: async ({
      action,
      payload,
      idempotencyKey,
    }: {
      action: 'approve' | 'reject';
      payload: TaskActionPayload;
      idempotencyKey: string;
    }) => runTaskAction(numericTaskId, action, payload, idempotencyKey),
    async onSuccess() {
      setActionError('');
      setStatusNotice('');
      setApproveOpen(false);
      setRejectOpen(false);
      await invalidateTaskCaches(queryClient, numericTaskId, detailQuery.data?.task.instanceId);
      navigate(returnPath(searchParams), { replace: true });
    },
    async onError(error) {
      if (isApiError(error) && error.status === 409) {
        setApproveOpen(false);
        setRejectOpen(false);
        setActionError('');
        setStatusNotice('任务状态已更新');
        await detailQuery.refetch();
        return;
      }
      setActionError(error instanceof Error ? error.message : '操作失败');
    },
  });

  const schema = useMemo(
    () => normalizeSchema(detailQuery.data?.schema),
    [detailQuery.data?.schema],
  );
  const values = useMemo(
    () => normalizeValues(detailQuery.data?.formData),
    [detailQuery.data?.formData],
  );
  const allowedActions = detailQuery.data?.allowedActions ?? [];
  const canApprove = allowedActions.includes('APPROVE');
  const canReject = allowedActions.includes('REJECT');
  const showActions = canApprove || canReject;

  if (!Number.isSafeInteger(numericTaskId) || numericTaskId <= 0) {
    return <PageError title="任务不存在" message="请返回任务中心重新打开。" />;
  }

  if (detailQuery.isPending) {
    return <PageSkeleton rows={5} />;
  }

  if (detailQuery.isError || !detailQuery.data) {
    return <PageError onRetry={() => void detailQuery.refetch()} />;
  }

  const detail = detailQuery.data;
  const task = detail.task;

  return (
    <AppPage
      title={task.formName || '任务详情'}
      description={`${task.applicantName}${task.applicantDepartment ? ` · ${task.applicantDepartment}` : ''}`}
      toolbar={
        <Button size="small" fill="outline" onClick={() => navigate(returnPath(searchParams))}>
          返回
        </Button>
      }
    >
      <div style={{ display: 'grid', gap: 12, paddingBottom: showActions ? 96 : 16 }}>
        {statusNotice ? (
          <p role="status" style={noticeStyle}>
            {statusNotice}
          </p>
        ) : null}

        <section style={sectionStyle} aria-label="任务状态">
          <span style={labelStyle}>节点</span>
          <strong>{task.nodeName}</strong>
          <span style={labelStyle}>任务状态</span>
          <span>{taskStatusLabel(task.taskStatus)}</span>
          <span style={labelStyle}>流程状态</span>
          <span>{instanceStatusLabel(task.instanceStatus)}</span>
        </section>

        <section style={sectionStyle} aria-label="表单内容">
          <strong>表单内容</strong>
          {schema.length > 0 ? (
            <DynamicFormRenderer
              schema={schema}
              values={values}
              mode="readonly"
              onValueChange={() => undefined}
            />
          ) : (
            <p style={metaStyle}>暂无表单字段</p>
          )}
        </section>

        <section style={sectionStyle} aria-label="附件">
          <strong>附件</strong>
          {detail.files.length === 0 ? (
            <p style={metaStyle}>暂无附件</p>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18, display: 'grid', gap: 6 }}>
              {detail.files.map((file) => (
                <li key={file.id}>
                  <a href={file.contentUrl} target="_blank" rel="noreferrer">
                    {file.name}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section style={sectionStyle} aria-label="流转记录">
          <strong>流转记录</strong>
          <TaskTimeline history={detail.history} processSnapshot={detail.processSnapshot} />
        </section>
      </div>

      {showActions ? (
        <div style={bottomActionStyle}>
          {canReject ? (
            <Button
              color="danger"
              fill="outline"
              disabled={actionMutation.isPending}
              onClick={() => {
                setActionError('');
                setRejectOpen(true);
              }}
            >
              驳回
            </Button>
          ) : (
            <span />
          )}
          {canApprove ? (
            <Button
              color="primary"
              disabled={actionMutation.isPending}
              onClick={() => {
                setActionError('');
                setApproveOpen(true);
              }}
            >
              同意
            </Button>
          ) : null}
        </div>
      ) : null}

      <ApproveSheet
        open={approveOpen}
        loading={actionMutation.isPending}
        error={approveOpen ? actionError : undefined}
        onClose={() => {
          if (!actionMutation.isPending) {
            setApproveOpen(false);
            setActionError('');
          }
        }}
        onSubmit={(payload, idempotencyKey) => {
          actionMutation.mutate({ action: 'approve', payload, idempotencyKey });
        }}
      />

      <RejectSheet
        open={rejectOpen}
        loading={actionMutation.isPending}
        error={rejectOpen ? actionError : undefined}
        rejectTargets={detail.rejectTargets}
        onClose={() => {
          if (!actionMutation.isPending) {
            setRejectOpen(false);
            setActionError('');
          }
        }}
        onSubmit={(payload, idempotencyKey) => {
          actionMutation.mutate({ action: 'reject', payload, idempotencyKey });
        }}
      />
    </AppPage>
  );
}

async function invalidateTaskCaches(
  queryClient: ReturnType<typeof useQueryClient>,
  taskId: number,
  instanceId?: number,
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }),
    queryClient.invalidateQueries({ queryKey: ['mobile', 'tasks'] }),
    queryClient.invalidateQueries({ queryKey: queryKeys.taskDetail(taskId) }),
    instanceId
      ? queryClient.invalidateQueries({ queryKey: queryKeys.instance(instanceId) })
      : Promise.resolve(),
  ]);
}

function returnPath(searchParams: URLSearchParams): string {
  const params = new URLSearchParams();
  const view = searchParams.get('returnView');
  const keyword = searchParams.get('returnKeyword');
  const status = searchParams.get('returnStatus');
  if (view) params.set('view', view);
  if (keyword) params.set('keyword', keyword);
  if (status) params.set('status', status);
  const query = params.toString();
  return query ? `/tasks?${query}` : '/tasks';
}

function normalizeSchema(schema: unknown): MobileSchemaNode[] {
  if (Array.isArray(schema)) {
    return schema as MobileSchemaNode[];
  }
  return [];
}

function normalizeValues(formData: Record<string, unknown> | null | undefined): MobileFormValues {
  if (!formData || typeof formData !== 'object' || Array.isArray(formData)) {
    return {};
  }
  return formData;
}

function taskStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待审批';
    case 'APPROVED':
      return '已同意';
    case 'REJECTED':
      return '已驳回';
    case 'SKIPPED':
      return '已跳过';
    case 'CC':
      return '抄送';
    default:
      return status;
  }
}

function instanceStatusLabel(status: string): string {
  switch (status) {
    case 'RUNNING':
      return '进行中';
    case 'APPROVED':
      return '已通过';
    case 'REJECTED':
      return '已驳回';
    case 'WITHDRAWN':
      return '已撤回';
    default:
      return status;
  }
}

export default TaskDetailPage;
