import { useState } from "react";
import type { ApprovalRecord, ApprovalSummary } from "./tasks.api";

export function ApprovalRecords({ records }: { records: ApprovalRecord[] }) {
  const [selected, setSelected] = useState<ApprovalRecord | null>(null);
  if (records.length === 0) {
    return <p className="muted small">暂无流转记录</p>;
  }
  return (
    <>
      <ol className="approval-records__list">
        {records.map((record) => {
          const processing = record.status === "PROCESSING";
          const rejected = record.status === "REJECTED";
          const submitted = record.status === "SUBMITTED";
          const itemTone = processing ? " approval-records__item--current" : rejected ? " approval-records__item--rejected" : " approval-records__item--done";
          const cardTone = processing ? " approval-record-card--current" : rejected ? " approval-record-card--rejected" : "";
          const statusTone = processing ? " approval-record-card__status--current" : rejected ? " approval-record-card__status--rejected" : " approval-record-card__status--done";
          const content = <><div className="approval-record-card__top"><div><span className="approval-record-card__node">{record.nodeName}</span><strong>{record.operatorName || "未记录"}</strong></div><span className={`approval-record-card__status${statusTone}`}>{recordStatusLabel(record.status)}</span></div><p>{record.comment || defaultComment(record.status)}</p><footer><span>{record.department || "未记录部门"} · {record.employeeNo || "未分配工号"}</span><time>{formatRecordTime(record.completedAt || record.receivedAt, processing)}</time></footer></>;
          return <li key={record.id} className={`approval-records__item${itemTone}`}><span className="approval-records__marker" aria-hidden="true" />{submitted ? <article className={`approval-record-card${cardTone}`}>{content}</article> : <button type="button" className={`approval-record-card approval-record-card--button${cardTone}`} onClick={() => setSelected(record)}>{content}</button>}</li>;
        })}
      </ol>
      {selected ? <RecordSheet record={selected} onClose={() => setSelected(null)} /> : null}
    </>
  );
}

export function approvalSummaryLabel(summary: ApprovalSummary) {
  return summary.complete
    ? "已完成"
    : `${summary.completedCount} 已完成 · ${summary.processingCount} 处理中`;
}

function RecordSheet({ record, onClose }: { record: ApprovalRecord; onClose: () => void }) {
  const processing = record.status === "PROCESSING";
  const rejected = record.status === "REJECTED";
  const chipTone = rejected ? " chip--danger-soft" : processing ? " chip--soft" : " chip--success-soft";
  return <><button type="button" className="sheet-mask is-open" aria-label="关闭审批记录详情" onClick={onClose} /><div className="sheet is-open" role="dialog" aria-modal="true" aria-label="审批记录详情"><div className="sheet__inner"><div className="sheet__title"><h3>{processing ? "当前节点详情" : "审批记录详情"}</h3><span className={`chip${chipTone} record-sheet__status`}>{recordStatusLabel(record.status)}</span></div><dl className="record-sheet__list"><div><dt>审批节点</dt><dd>{record.nodeName}</dd></div><div><dt>审批人</dt><dd>{record.operatorName} · {record.employeeNo || "未分配工号"}</dd></div><div><dt>所属部门</dt><dd>{record.department || "未记录"}</dd></div><div><dt>接收时间</dt><dd>{formatDateTime(record.receivedAt)}</dd></div>{record.completedAt ? <div><dt>完成时间</dt><dd>{formatDateTime(record.completedAt)}</dd></div> : <div><dt>当前状态</dt><dd>{processing ? "等待处理" : recordStatusLabel(record.status)}</dd></div>}<div className="record-sheet__stack"><dt>审批意见</dt><dd>{record.comment || defaultComment(record.status)}</dd></div></dl><button className="btn btn--ghost btn--block record-sheet__close" type="button" onClick={onClose}>关闭</button></div></div></>;
}

function recordStatusLabel(status: string) {
  return ({ SUBMITTED: "已提交", PROCESSING: "审批中", APPROVED: "已通过", REJECTED: "已驳回", RETURNED: "待修改", RESUBMITTED: "已重新提交" } as Record<string, string>)[status] ?? status;
}

function defaultComment(status: string) {
  return ({ SUBMITTED: "已完成表单填写并提交审批。", PROCESSING: "等待处理当前审批节点。", APPROVED: "已完成本节点审批。", REJECTED: "已驳回至直接上一级。", RETURNED: "原单已退回，等待修改后重新提交。", RESUBMITTED: "原单修改完成并重新提交审批。" } as Record<string, string>)[status] ?? "审批节点状态已更新。";
}

function formatRecordTime(value: string, received: boolean) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  const dateTime = `${month}-${day} ${hour}:${minute}`;
  return received ? `${dateTime} 接收` : dateTime;
}

function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

export default ApprovalRecords;
