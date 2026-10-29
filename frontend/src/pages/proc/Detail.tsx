import { Alert, Button, Result, Skeleton } from 'antd';
import { useParams, useModel } from '@umijs/max';
import { CAPABILITY, hasCapability } from '../../authz';
import { ActivityPanel } from './ActivityPanel';
import { AutomationJobsPanel } from './AutomationJobsPanel';
import { DetailHero } from './DetailHero';
import { DetailModals } from './DetailModals';
import { type DetailCapabilities, findApproverNodes } from './detailModel';
import { ProcessFormPanel } from './ProcessFormPanel';
import { ProcessProgressPanel } from './ProcessProgressPanel';
import { useDetailActions } from './useDetailActions';
import { useInstanceDetail } from './useInstanceDetail';
import './Detail.less';

export default function DetailPage() {
  const { id } = useParams();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser as any;
  const currentUserId = currentUser?.id;

  const {
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
  } = useInstanceDetail(id, currentUserId);

  const can: DetailCapabilities = {
    approve: hasCapability(currentUser, CAPABILITY.workflowTaskApprove),
    reject: hasCapability(currentUser, CAPABILITY.workflowTaskReject),
    withdraw: hasCapability(currentUser, CAPABILITY.workflowInstanceWithdraw),
    discard: hasCapability(currentUser, CAPABILITY.workflowInstanceDiscard),
    override: hasCapability(currentUser, CAPABILITY.workflowInstanceOverride),
    retryAutomation: hasCapability(currentUser, CAPABILITY.workflowAutomationRetry),
  };

  const isStarter = currentUserId != null && instance?.startedBy === currentUserId;
  const isRunner = instance?.status === 'RUNNING';
  const isRework = instance?.currentNodeId === '__rework__';
  const pendingTask = tasks.find(
    (task: any) => task.status === 'PENDING' && task.taskType !== 'REWORK',
  );

  const actions = useDetailActions({
    id,
    tasks,
    myPending,
    pendingTask,
    isStarter,
    hasEditableFields,
    editableValues,
    currentFormModes,
    formSchema,
  });

  if (detailQuery.isLoading) {
    return <ProcessDetailLoading />;
  }
  if (detailQuery.isError || !data || !instance || !presentation) {
    return (
      <div className="process-detail-page">
        <Result
          status="error"
          title="流程详情加载失败"
          subTitle="请检查网络连接后重新加载。"
          extra={<Button type="primary" onClick={() => detailQuery.refetch()}>重新加载</Button>}
        />
      </div>
    );
  }

  const rejectTargets = snapshotObj ? findApproverNodes(snapshotObj) : [];
  const automationJobs = (data as any).automationJobs ?? [];

  return (
    <>
      <div className="process-detail-page">
        <div className="process-detail-shell">
          <DetailHero
            instance={instance}
            presentation={presentation}
            snapshotObj={snapshotObj}
            fullVisibility={fullVisibility}
            myPending={myPending}
            isRunner={isRunner}
            isStarter={isStarter}
            isRework={isRework}
            can={can}
            hasPendingTask={!!pendingTask}
            onApprove={actions.openApprove}
            onReject={actions.openReject}
            onWithdraw={actions.openWithdraw}
            onDiscard={actions.openDiscard}
            onOverride={actions.openOverride}
          />

          {!fullVisibility && (
            <Alert
              type="info"
              showIcon
              title="当前账号仅可查看流程摘要"
              description="表单内容、附件、自动化作业和流程操作已隐藏。"
            />
          )}
          {fullVisibility && isRework && (
            <Alert
              type="info"
              showIcon
              title="流程待修改"
              description="请到移动端修改原表单并重新提交，原实例和单号已保留。"
            />
          )}

          <ProcessProgressPanel stages={progressStages} />

          <div className={`process-detail-grid${fullVisibility ? '' : ' process-detail-grid--single'}`}>
            {fullVisibility && (
              <main className="process-detail-main">
                <ProcessFormPanel
                  schema={formSchema}
                  fieldModes={currentFormModes}
                  value={myPending ? editableValues : initialFormData}
                  onChange={hasEditableFields ? setEditableValues : undefined}
                  instanceId={Number(id)}
                  hasEditableFields={hasEditableFields}
                />

                {automationJobs.length > 0 && (
                  <AutomationJobsPanel
                    jobs={automationJobs}
                    snapshotObj={snapshotObj}
                    canRetry={can.retryAutomation}
                    onRetry={actions.retryAutomationJob}
                  />
                )}
              </main>
            )}

            <aside className="process-detail-sidebar" aria-label="流程流转轨迹">
              <ActivityPanel items={activityItems} />
            </aside>
          </div>
        </div>
      </div>

      <DetailModals
        actions={actions}
        rejectTargets={rejectTargets}
        pendingTaskId={myPending?.id}
      />
    </>
  );
}

function ProcessDetailLoading() {
  return (
    <div className="process-detail-page">
      <div className="process-detail-shell process-detail-loading">
        <Skeleton active avatar paragraph={{ rows: 4 }} />
        <Skeleton active paragraph={{ rows: 10 }} />
      </div>
    </div>
  );
}
