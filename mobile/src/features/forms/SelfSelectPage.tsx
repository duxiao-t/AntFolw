import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Selector } from 'antd-mobile';
import { useNavigate, useParams } from 'react-router-dom';
import { queryKeys } from '../../shared/api/queryKeys';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { fetchMobileForm } from './drafts.api';
import {
  findSelfSelectRules,
  updateSelfSelected,
  useSubmitFlowStore,
} from './submitFlow.store';

const listStyle: React.CSSProperties = {
  display: 'grid',
  gap: 10,
};

const nodeStyle: React.CSSProperties = {
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
  padding: 12,
  display: 'grid',
  gap: 10,
};

const ruleStyle: React.CSSProperties = {
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

const errorStyle: React.CSSProperties = {
  color: 'var(--af-color-danger)',
  fontSize: '0.8125rem',
};

const bottomActionStyle: React.CSSProperties = {
  position: 'fixed',
  left: 0,
  right: 0,
  bottom: 0,
  display: 'grid',
  padding: '12px 16px calc(16px + env(safe-area-inset-bottom))',
  background: 'var(--af-color-bg)',
  boxShadow: '0 -8px 20px rgba(0,0,0,0.08)',
};

export function SelfSelectPage() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const flow = useSubmitFlowStore();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const formQuery = useQuery({
    queryKey: queryKeys.form(code),
    queryFn: () => fetchMobileForm(code),
    enabled: code.length > 0,
    retry: 0,
  });
  const rules = useMemo(
    () => findSelfSelectRules(formQuery.data?.process),
    [formQuery.data?.process],
  );

  if (!flow.formCode || flow.formCode !== code) {
    return (
      <AppPage title="选择审批人">
        <p>当前提交信息已失效，请返回表单重新提交。</p>
        <Button block color="primary" onClick={() => navigate(`/forms/${encodeURIComponent(code)}`)}>
          返回表单
        </Button>
      </AppPage>
    );
  }

  if (formQuery.isPending) {
    return <PageSkeleton rows={4} />;
  }

  if (formQuery.isError) {
    return <PageError onRetry={() => void formQuery.refetch()} />;
  }

  return (
    <AppPage title="选择审批人" style={{ paddingBottom: 104 }}>
      <div style={listStyle}>
        {rules.map((rule) => (
          <section key={rule.nodeId} style={nodeStyle}>
            <div>
              <strong>{rule.name}</strong>
              <div style={ruleStyle}>{rule.multiple ? '多选' : '单选'}</div>
            </div>
            <Selector
              multiple={rule.multiple}
              options={rule.assignees.map((assignee) => ({
                label: assignee.name,
                value: assignee.id,
              }))}
              value={flow.selfSelected[rule.nodeId] ?? []}
              onChange={(next) => {
                updateSelfSelected(rule.nodeId, next.map(Number));
                setErrors((current) => {
                  const copy = { ...current };
                  delete copy[rule.nodeId];
                  return copy;
                });
              }}
            />
            {errors[rule.nodeId] ? (
              <span role="alert" style={errorStyle}>{errors[rule.nodeId]}</span>
            ) : null}
          </section>
        ))}
      </div>
      <div style={bottomActionStyle}>
        <Button block color="primary" size="large" onClick={confirmSelection}>
          确认选择
        </Button>
      </div>
    </AppPage>
  );

  function confirmSelection() {
    const nextErrors = Object.fromEntries(
      rules
        .filter((rule) => (useSubmitFlowStore.getState().selfSelected[rule.nodeId] ?? []).length === 0)
        .map((rule) => [rule.nodeId, `请选择${rule.name}`]),
    );
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      return;
    }
    void navigate(`/forms/${encodeURIComponent(code)}/confirm`);
  }
}

export default SelfSelectPage;
