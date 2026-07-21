import { useQuery } from '@tanstack/react-query';
import { Button } from 'antd-mobile';
import { useNavigate, useParams } from 'react-router-dom';
import { queryKeys } from '../../shared/api/queryKeys';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { fetchMobileInstanceDetail } from './processes.api';

const sectionStyle: React.CSSProperties = {
  background: 'var(--af-color-surface)',
  borderRadius: 'var(--af-radius-surface)',
  padding: 12,
  display: 'grid',
  gap: 8,
};

const labelStyle: React.CSSProperties = {
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

export function ProcessDetailPage() {
  const { instanceId = '' } = useParams();
  const navigate = useNavigate();
  const numericInstanceId = Number(instanceId);
  const instanceQuery = useQuery({
    queryKey: queryKeys.instance(numericInstanceId),
    queryFn: () => fetchMobileInstanceDetail(numericInstanceId),
    enabled: Number.isSafeInteger(numericInstanceId) && numericInstanceId > 0,
    retry: 0,
  });

  if (!Number.isSafeInteger(numericInstanceId) || numericInstanceId <= 0) {
    return <PageError title="流程不存在" message="请返回列表重新打开。" />;
  }

  if (instanceQuery.isPending) {
    return <PageSkeleton rows={4} />;
  }

  if (instanceQuery.isError) {
    return <PageError onRetry={() => void instanceQuery.refetch()} />;
  }

  const instance = instanceQuery.data;
  return (
    <AppPage
      title="流程详情"
      toolbar={<Button onClick={() => navigate('/workbench')}>返回工作台</Button>}
    >
      <section style={sectionStyle}>
        <span style={labelStyle}>表单</span>
        <strong>{instance.formName ?? `流程#${instance.id}`}</strong>
        <span style={labelStyle}>状态</span>
        <span>{instance.status}</span>
      </section>
    </AppPage>
  );
}

export default ProcessDetailPage;
