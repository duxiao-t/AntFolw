import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button } from 'antd-mobile';
import { useNavigate, useParams } from 'react-router-dom';
import { queryKeys } from '../../shared/api/queryKeys';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { useAuthStore } from '../auth/auth.store';
import { fetchMobileForm } from './drafts.api';
import { removeRecoveryDraft } from './recoveryDraft.store';
import { getFieldDefinition } from './schema/fieldRegistry';
import type { MobileFormValues, MobileSchemaNode } from './schema/types';
import { startMobileInstance } from './start.api';
import {
  clearIdempotencyKeyForPayload,
  findSelfSelectRules,
  formSchemaWithoutSelfSelectRules,
  idempotencyKeyForPayload,
  selectedAssigneeNames,
  useSubmitFlowStore,
} from './submitFlow.store';

const sectionStyle: React.CSSProperties = {
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
  padding: 12,
  display: 'grid',
  gap: 10,
};

const listStyle: React.CSSProperties = {
  display: 'grid',
  gap: 10,
};

const rowStyle: React.CSSProperties = {
  display: 'grid',
  gap: 4,
};

const labelStyle: React.CSSProperties = {
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

const errorStyle: React.CSSProperties = {
  color: 'var(--af-color-danger)',
};

const bottomActionStyle: React.CSSProperties = {
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

export function SubmitConfirmPage() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const flow = useSubmitFlowStore();
  const resetFlow = useSubmitFlowStore((state) => state.reset);
  const [error, setError] = useState('');
  const formQuery = useQuery({
    queryKey: queryKeys.form(code),
    queryFn: () => fetchMobileForm(code),
    enabled: code.length > 0,
    retry: 0,
  });

  const formRows = useMemo(
    () => summarizeRows(formSchemaWithoutSelfSelectRules(formQuery.data?.schema ?? []), flow.values),
    [flow.values, formQuery.data?.schema],
  );
  const selfSelectedRows = useMemo(
    () => selectedAssigneeNames(findSelfSelectRules(formQuery.data?.process), flow.selfSelected),
    [flow.selfSelected, formQuery.data?.process],
  );

  const submitMutation = useMutation({
    mutationFn: () => {
      setError('');
      return startMobileInstance({
        formCode: flow.formCode ?? code,
        values: flow.values,
        selfSelected: flow.selfSelected,
        draftId: flow.draftId,
        idempotencyKey: idempotencyKeyForCurrentPayload(),
      });
    },
    onSuccess(result) {
      clearIdempotencyKeyForPayload(currentPayload());
      if (user && flow.formCode) {
        removeRecoveryDraft(user.id, flow.formCode, flow.draftId);
      }
      resetFlow();
      void navigate(`/forms/${encodeURIComponent(code)}/success/${result.instanceId}`, { replace: true });
    },
    onError(errorValue) {
      setError(errorValue instanceof Error ? errorValue.message : '提交失败');
    },
  });

  if (!flow.formCode || flow.formCode !== code) {
    return (
      <AppPage title="提交确认">
        <p>当前提交信息已失效，请返回表单重新提交。</p>
        <Button block color="primary" onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}>
          返回表单
        </Button>
      </AppPage>
    );
  }

  if (formQuery.isPending) {
    return <PageSkeleton rows={5} />;
  }

  if (formQuery.isError) {
    return <PageError onRetry={() => void formQuery.refetch()} />;
  }

  return (
    <AppPage title="提交确认" style={{ paddingBottom: 120 }}>
      <div style={listStyle}>
        <section style={sectionStyle}>
          <strong>表单内容</strong>
          {formRows.map((row) => (
            <div key={row.id} style={rowStyle}>
              <span style={labelStyle}>{row.label}</span>
              <span>{row.value}</span>
            </div>
          ))}
        </section>
        {selfSelectedRows.length > 0 ? (
          <section style={sectionStyle}>
            <strong>审批人</strong>
            {selfSelectedRows.map((row) => (
              <div key={row.nodeId} style={rowStyle}>
                <span style={labelStyle}>{row.name}</span>
                <span>{row.names.join('、')}</span>
              </div>
            ))}
          </section>
        ) : null}
      </div>
      {error ? <p role="alert" style={errorStyle}>{error}</p> : null}
      <div style={bottomActionStyle}>
        <Button
          block
          color="primary"
          size="large"
          loading={submitMutation.isPending}
          onClick={() => submitMutation.mutate()}
        >
          {error ? '重试提交' : '提交'}
        </Button>
      </div>
    </AppPage>
  );

  function idempotencyKeyForCurrentPayload() {
    return idempotencyKeyForPayload(currentPayload());
  }

  function currentPayload() {
    return JSON.stringify({
      formCode: flow.formCode ?? code,
      data: flow.values,
      selfSelected: flow.selfSelected,
      draftId: flow.draftId,
    });
  }
}

function summarizeRows(schema: MobileSchemaNode[], values: MobileFormValues) {
  return schema.flatMap((node) => summarizeNode(node, values));
}

function summarizeNode(node: MobileSchemaNode, values: MobileFormValues): Array<{
  id: string;
  label: string;
  value: string;
}> {
  if (node.type === 'description') {
    return [];
  }
  if (node.children && node.type !== 'table_list') {
    return node.children.flatMap((child) => summarizeNode(child, values));
  }
  return [{
    id: node.id,
    label: node.label ?? node.id,
    value: getFieldDefinition(node.type).summarize(node, values[node.id]),
  }];
}

export default SubmitConfirmPage;
