import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { AppPage } from '../../shared/ui/AppPage';
import { PageError, PageSkeleton } from '../../shared/ui/PageStates';
import { ConfirmSummaryList } from './components/ConfirmSummaryList';
import { fetchMobileDirectSubmission } from './direct-submissions.api';

export function DirectSubmissionDetailPage() {
  const { submissionId = '' } = useParams();
  const navigate = useNavigate();
  const id = Number(submissionId);
  const query = useQuery({
    queryKey: ['mobile', 'submissions', id],
    queryFn: () => fetchMobileDirectSubmission(id),
    enabled: Number.isSafeInteger(id) && id > 0,
    retry: 0,
  });

  if (!Number.isSafeInteger(id) || id <= 0) {
    return <PageError title="填报记录不存在" message="请返回我的发起重新打开。" />;
  }
  if (query.isPending) return <PageSkeleton rows={5} />;
  if (query.isError || !query.data) return <PageError onRetry={() => void query.refetch()} />;

  const submission = query.data;
  return (
    <AppPage title="填报详情" onBack={() => navigate('/tasks?view=process')} contentClassName="approval-detail-page">
      <section className="approval-hero detail-hero--bleed">
        <div className="approval-hero__title-block"><span className="approval-hero__label">表单名称</span><h1>{submission.formName}</h1></div>
        <dl className="approval-hero__meta">
          <div><dt>状态</dt><dd>已填报</dd></div>
          <div><dt>流水号</dt><dd>{submission.businessNo || '未生成'}</dd></div>
          <div className="approval-hero__meta-wide"><dt>提交时间</dt><dd>{formatDateTime(submission.submittedAt)}</dd></div>
        </dl>
      </section>
      <section className="approval-panel form-detail-panel">
        <header className="approval-panel__head form-detail-panel__head"><div><h2>填报内容</h2><p>此表单无需审批，提交后即完成。</p></div></header>
        <ConfirmSummaryList
          schema={submission.schema}
          values={submission.formData}
          extraFiles={submission.files}
          emptyText="暂无填报字段"
        />
      </section>
    </AppPage>
  );
}

function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

export default DirectSubmissionDetailPage;
