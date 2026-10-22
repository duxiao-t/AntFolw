import {
  LoopOutline,
  MailOutline,
  SetOutline,
  UserOutline,
} from 'antd-mobile-icons';
import type { ReactNode } from 'react';
import {
  defaultComment,
  formatRecordTime,
  itemTone,
  operationSourceLabel,
  operationTag,
  recordStatusLabel,
  statusTone,
  visualKind,
} from './approvalRecordLabels';
import type { TimelineRecord } from './approvalRecordsTimeline';
import type { ApprovalRecord } from './tasks.api';

export function RecordItem({
  record,
  onSelect,
}: {
  record: TimelineRecord;
  onSelect: (record: ApprovalRecord) => void;
}) {
  return (
    <li className={`approval-records__item${itemTone(record)}`}>
      <TrackMarker record={record} />
      <RecordCard record={record} onSelect={onSelect} />
    </li>
  );
}

function TrackMarker({ record }: { record: ApprovalRecord }) {
  const kind = visualKind(record);
  let icon: ReactNode = null;
  if (kind === 'operation') icon = <UserOutline />;
  else if (kind === 'cc') icon = <MailOutline />;
  else if (kind === 'automation') icon = <SetOutline />;
  else if (kind === 'rejected') icon = <LoopOutline />;
  return (
    <span
      className={`approval-records__marker approval-records__marker--${kind}`}
      aria-hidden="true"
    >
      {icon}
    </span>
  );
}

export function RecordCard({
  record,
  compact = false,
  onSelect,
}: {
  record: TimelineRecord;
  compact?: boolean;
  onSelect: (record: ApprovalRecord) => void;
}) {
  const submitted = record.status === 'SUBMITTED';
  const automation = record.recordKind === 'AUTOMATION';
  const kind = visualKind(record);
  const tag = operationTag(record);
  const content = (
    <>
      <div className="approval-record-card__top">
        <div>
          <span className="approval-record-card__node">{record.nodeName}</span>
          <strong>
            {record.operatorName || '未记录'}
            {record.sourceOperatorName ? (
              <em>
                （{operationSourceLabel(record.operationKind)}：
                {record.sourceOperatorName}）
              </em>
            ) : null}
          </strong>
        </div>
        <span
          className={`approval-record-card__status approval-record-card__status--${statusTone(record)}`}
        >
          {recordStatusLabel(record)}
        </span>
      </div>
      <p className="approval-record-card__comment">{record.comment || defaultComment(record)}</p>
      <footer>
        <span className="approval-record-card__meta">
          <span>
            {record.department || (automation ? '系统' : '未记录部门')} ·{' '}
            {record.employeeNo || (automation ? '自动执行' : '未分配工号')}
          </span>
          <time>
            {formatRecordTime(
              record.completedAt || record.receivedAt,
              record.status === 'PROCESSING' || record.status === 'RETURNED',
            )}
          </time>
        </span>
        {tag ? (
          <span
            className={`approval-record-card__tag approval-record-card__tag--${kind}`}
          >
            {tag}
          </span>
        ) : null}
      </footer>
    </>
  );
  const className = `approval-record-card approval-record-card--${kind}${compact ? ' approval-record-card--compact' : ''}`;
  return submitted || automation ? (
    <article className={className}>{content}</article>
  ) : (
    <button
      type="button"
      className={`${className} approval-record-card--button`}
      onClick={() => onSelect(record)}
    >
      {content}
    </button>
  );
}
