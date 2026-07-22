import { useMemo, useRef, useState } from 'react';
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
import { ProcessSnapshotTimeline } from './ProcessSnapshotTimeline';
import { fetchMobileInstanceDetail, withdrawMobileInstance } from './processes.api';

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

const errorStyle: CSSProperties = {
  margin: 0,
  color: 'var(--af-color-danger)',
};

export function ProcessDetailPage() {
  const { instanceId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericInstanceId = Number(instanceId);
  const [statusNotice, setStatusNotice] = useState('');
  const [actionError, setActionError] = useState('');
  const withdrawKeyRef = useRef(createIdempotencyKey());

  const instanceQuery = useQuery({
    queryKey: queryKeys.instance(numericInstanceId),
    queryFn: () => fetchMobileInstanceDetail(numericInstanceId),
    enabled: Number.isSafeInteger(numericInstanceId) && numericInstanceId > 0,
    retry: 0,
  });

  const withdrawMutation = useMutation({
    mutationFn: () => withdrawMobileInstance(numericInstanceId, withdrawKeyRef.current),
    async onSuccess() {
      setActionError('');
      setStatusNotice('');
      await invalidateProcessCaches(queryClient, numericInstanceId);
      navigate(returnPath(searchParams), { replace: true });
    },
    async onError(error) {
      if (isApiError(error) && (error.status === 409 || error.body.code === 'ALREADY_ACTED')) {
        setActionError('');
        setStatusNotice('流程状态已更新');
        await instanceQuery.refetch();
        return;
      }
      setActionError(error instanceof Error ? error.message : '撤回失败');
    },
  });

  const schema = useMemo(
    () => normalizeSchema(instanceQuery.data?.schema),
    [instanceQuery.data?.schema],
  );
  const values = useMemo(
    () => normalizeValues(instanceQuery.data?.formData),
    [instanceQuery.data?.formData],
  );

  if (!Number.isSafeInteger(numericInstanceId) || numericInstanceId <= 0) {
    return <PageError title="流程不存在" message="请返回列表重新打开。" />;
  }

  if (instanceQuery.isPending) {
    return <PageSkeleton rows={4} />;
  }

  if (instanceQuery.isError || !instanceQuery.data) {
    return <PageError onRetry={() => void instanceQuery.refetch()} />;
  }

  const instance = instanceQuery.data;
  const history = instance.history ?? [];
  const files = instance.files ?? [];
  const canWithdraw = instance.canWithdraw;

  return (
    <AppPage
      title={instance.formName ?? '流程详情'}
      description={`状态：${instanceStatusLabel(instance.status)}`}
      toolbar={
        <Button size="small" fill="outline" onClick={() => navigate(returnPath(searchParams))}>
          返回
        </Button>
      }
    >
      <div style={{ display: 'grid', gap: 12, paddingBottom: canWithdraw ? 96 : 16 }}>
        {statusNotice ? (
          <p role="status" style={noticeStyle}>
            {statusNotice}
          </p>
        ) : null}
        {actionError ? (
          <p role="alert" style={errorStyle}>
            {actionError}
          </p>
        ) : null}

        <section style={sectionStyle} aria-label="流程状态">
          <span style={labelStyle}>表单</span>
          <strong>{instance.formName ?? `流程#${instance.id}`}</strong>
          <span style={labelStyle}>状态</span>
          <span>{instanceStatusLabel(instance.status)}</span>
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
          {files.length === 0 ? (
            <p style={metaStyle}>暂无附件</p>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18, display: 'grid', gap: 6 }}>
              {files.map((file) => (
                <li key={file.id}>
                  <a href={file.contentUrl} target="_blank" rel="noreferrer">
                    {file.name}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section style={sectionStyle} aria-label="流程进度">
          <strong>流程进度</strong>
          <ProcessSnapshotTimeline
            history={history}
            processSnapshot={instance.processSnapshot}
          />
        </section>
      </div>

      {canWithdraw ? (
        <div style={bottomActionStyle}>
          <Button
            color="danger"
            loading={withdrawMutation.isPending}
            disabled={withdrawMutation.isPending}
            onClick={() => {
              if (!window.confirm('确认撤回该流程？撤回后不可恢复。')) {
                return;
              }
              setActionError('');
              withdrawKeyRef.current = createIdempotencyKey();
              withdrawMutation.mutate();
            }}
          >
            撤回
          </Button>
        </div>
      ) : null}
    </AppPage>
  );
}

async function invalidateProcessCaches(
  queryClient: ReturnType<typeof useQueryClient>,
  instanceId: number,
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap }),
    queryClient.invalidateQueries({ queryKey: ['mobile', 'tasks'] }),
    queryClient.invalidateQueries({ queryKey: queryKeys.instance(instanceId) }),
  ]);
}

function returnPath(searchParams: URLSearchParams): string {
  const params = new URLSearchParams();
  const view = searchParams.get('returnView') ?? 'process';
  const keyword = searchParams.get('returnKeyword');
  const status = searchParams.get('returnStatus');
  params.set('view', view);
  if (keyword) params.set('keyword', keyword);
  if (status) params.set('status', status);
  return `/tasks?${params.toString()}`;
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

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `withdraw-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default ProcessDetailPage;
