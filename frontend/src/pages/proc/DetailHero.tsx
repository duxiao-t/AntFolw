import { ArrowLeftOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { history } from '@umijs/max';
import type { DetailCapabilities } from './detailModel';
import {
  formatDateTime,
  formatDuration,
  type InstancePresentation,
  instanceStatus,
  nodeLabel,
  type ProcessNode,
} from './detailPresentation';
import { TechnicalValue } from './detailSections';

/** 页面头部：身份、状态、可执行动作、四项元信息、技术信息。 */
export function DetailHero({
  instance,
  presentation,
  snapshotObj,
  fullVisibility,
  myPending,
  isRunner,
  isStarter,
  isRework,
  can,
  hasPendingTask,
  onApprove,
  onReject,
  onWithdraw,
  onOverride,
}: {
  instance: any;
  presentation: InstancePresentation;
  snapshotObj: ProcessNode | null;
  fullVisibility: boolean;
  myPending?: any;
  isRunner: boolean;
  isStarter: boolean;
  isRework: boolean;
  can: DetailCapabilities;
  hasPendingTask: boolean;
  onApprove(): void;
  onReject(taskId: number): void;
  onWithdraw(): void;
  onOverride(): void;
}) {
  const statusView = instanceStatus(instance.status, instance.currentNodeId);
  const currentNodeName = isRework
    ? '等待发起人修改原单'
    : instance.status === 'APPROVED'
      ? '流程已完成'
      : instance.status === 'REJECTED'
        ? '流程已驳回'
        : presentation.currentNodeName || nodeLabel(snapshotObj, instance.currentNodeId);
  const applicantIdentity = [
    presentation.applicantDepartment,
    presentation.applicantEmployeeNo ? `工号 ${presentation.applicantEmployeeNo}` : null,
  ].filter(Boolean).join(' · ');

  return (
    <header className="process-detail-hero">
      <div className="process-detail-hero__top">
        <div className="process-detail-identity">
          <Button
            type="text"
            className="process-detail-back"
            icon={<ArrowLeftOutlined />}
            aria-label="返回上一页"
            onClick={() => history.back()}
          />
          <div>
            <p className="process-detail-kicker">ANTFLOW / INSTANCE</p>
            <div className="process-detail-title-row">
              <h1>{presentation.formName || '未命名流程'}</h1>
              <span className={`process-status process-status--${statusView.tone}`}>
                {statusView.label}
              </span>
            </div>
            <p className="process-detail-subtitle">
              {presentation.businessNo ? `单号 ${presentation.businessNo}` : '尚未生成业务单号'}
            </p>
          </div>
        </div>
        <div className="process-detail-actions">
          {myPending && isRunner && fullVisibility && (
            <>
              {can.approve && (
                <Button type="primary" onClick={onApprove}>同意</Button>
              )}
              {can.reject && (
                <Button
                  danger
                  disabled={!!myPending.parallelId}
                  title={myPending.parallelId ? '并行审批节点不允许驳回' : undefined}
                  onClick={() => onReject(myPending.id)}
                >
                  驳回
                </Button>
              )}
            </>
          )}
          {can.withdraw && isStarter && isRunner && !isRework && fullVisibility && (
            <Button onClick={onWithdraw}>撤回流程</Button>
          )}
          {can.override && isRunner && hasPendingTask && fullVisibility && (
            <Button danger icon={<ThunderboltOutlined />} onClick={onOverride}>
              紧急介入
            </Button>
          )}
        </div>
      </div>

      <dl className="process-detail-meta">
        <div>
          <dt>当前进度</dt>
          <dd>{currentNodeName}</dd>
          <small>{statusView.label}</small>
        </div>
        <div>
          <dt>发起人</dt>
          <dd>{presentation.applicantName || `用户 #${instance.startedBy}`}</dd>
          <small>{applicantIdentity || '未记录部门与工号'}</small>
        </div>
        <div>
          <dt>发起时间</dt>
          <dd>{formatDateTime(instance.startedAt)}</dd>
          <small>以系统记录为准</small>
        </div>
        <div>
          <dt>{instance.finishedAt ? '完成时间' : '已运行'}</dt>
          <dd>{instance.finishedAt
            ? formatDateTime(instance.finishedAt)
            : formatDuration(instance.startedAt)}</dd>
          <small>{instance.finishedAt
            ? `总耗时 ${formatDuration(instance.startedAt, instance.finishedAt)}`
            : '流程仍在处理中'}</small>
        </div>
      </dl>

      {fullVisibility && (
        <details className="process-technical">
          <summary>技术信息 <span>仅用于排障</span></summary>
          <dl>
            <TechnicalValue label="实例 ID" value={instance.id} />
            <TechnicalValue label="流程定义" value={instance.procDefId} />
            <TechnicalValue label="流程版本" value={instance.processDefVersion} />
            <TechnicalValue label="当前节点 ID" value={instance.currentNodeId} />
            <TechnicalValue label="引擎版本" value={instance.engineVersion} />
          </dl>
        </details>
      )}
    </header>
  );
}
