import {
  defaultComment,
  formatDateTime,
  recordStatusLabel,
} from './approvalRecordLabels';
import type { ApprovalRecord } from './tasks.api';

export function RecordSheet({
  record,
  onClose,
}: {
  record: ApprovalRecord;
  onClose: () => void;
}) {
  const processing =
    record.status === 'PROCESSING' || record.status === 'RETURNED';
  const rejected =
    record.status === 'REJECTED' ||
    record.status === 'FAILED' ||
    record.operationKind === 'INVALIDATED';
  const chipTone = rejected
    ? ' chip--danger-soft'
    : processing
      ? ' chip--soft'
      : ' chip--success-soft';
  const personLabel =
    record.recordKind === 'CC'
      ? '抄送人'
      : record.recordKind === 'AUTOMATION'
        ? '执行者'
        : '审批人';
  return (
    <>
      <button
        type="button"
        className="sheet-mask is-open"
        aria-label="关闭审批记录详情"
        onClick={onClose}
      />
      <div
        className="sheet is-open"
        role="dialog"
        aria-modal="true"
        aria-label="审批记录详情"
      >
        <div className="sheet__inner">
          <div className="sheet__title">
            <h3>{processing ? '当前节点详情' : '审批记录详情'}</h3>
            <span className={`chip${chipTone} record-sheet__status`}>
              {recordStatusLabel(record)}
            </span>
          </div>
          <dl className="record-sheet__list">
            <div>
              <dt>审批节点</dt>
              <dd>{record.nodeName}</dd>
            </div>
            <div>
              <dt>{personLabel}</dt>
              <dd>
                {record.operatorName} · {record.employeeNo || '未分配工号'}
              </dd>
            </div>
            <div>
              <dt>所属部门</dt>
              <dd>{record.department || '未记录'}</dd>
            </div>
            <div>
              <dt>接收时间</dt>
              <dd>{formatDateTime(record.receivedAt)}</dd>
            </div>
            {record.completedAt ? (
              <div>
                <dt>完成时间</dt>
                <dd>{formatDateTime(record.completedAt)}</dd>
              </div>
            ) : (
              <div>
                <dt>当前状态</dt>
                <dd>{processing ? '等待处理' : recordStatusLabel(record)}</dd>
              </div>
            )}
            <div className="record-sheet__stack">
              <dt>审批意见</dt>
              <dd>{record.comment || defaultComment(record)}</dd>
            </div>
          </dl>
          <button
            className="btn btn--ghost btn--block record-sheet__close"
            type="button"
            onClick={onClose}
          >
            关闭
          </button>
        </div>
      </div>
    </>
  );
}
