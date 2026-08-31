import { useEffect, useRef, useState } from "react";
import { useFocusTrap } from "../../shared/a11y/useFocusTrap";

const REASONS = ["数据不一致", "附件缺失", "备注不完整", "需上级签批"];

export function RejectSheet({ open, loading, error, rejectTargets = [], onClose, onSubmit }: { open: boolean; loading: boolean; error?: string; rejectTargets?: Array<{ nodeId: string; name: string }>; onClose: () => void; onSubmit: (payload: { comment: string; rejectToNodeId?: string }, idempotencyKey: string) => void }) {
  const [comment, setComment] = useState("");
  const [rejectToNodeId, setRejectToNodeId] = useState("");
  const [localError, setLocalError] = useState("");
  const keyRef = useRef(createIdempotencyKey());
  const panelRef = useRef<HTMLDivElement>(null);
  useFocusTrap(open, panelRef, onClose);
  useEffect(() => { if (open) { setComment(""); setRejectToNodeId(""); setLocalError(""); keyRef.current = createIdempotencyKey(); } }, [open]);
  if (!open) return null;
  return <><button type="button" className="sheet-mask is-open" aria-label="关闭" onClick={onClose} /><div className="sheet is-open" ref={panelRef} role="dialog" aria-modal="true" aria-label="驳回审批"><div className="sheet__inner"><div className="sheet__title"><h3>驳回本次申请</h3><span className="chip chip--danger-soft" style={{ marginLeft: "auto" }}>必填原因</span></div><p className="muted small approval-sheet__hint">选择退回节点；不选择时退回直接上一级。</p><div className="sheet__form">{rejectTargets.length > 0 ? <label className="field"><span>退回到</span><select value={rejectToNodeId} disabled={loading} onChange={(event) => setRejectToNodeId(event.currentTarget.value)}><option value="">直接上一级</option>{rejectTargets.map((target) => <option key={target.nodeId} value={target.nodeId}>{target.name}</option>)}</select></label> : null}<div className="chip-row">{REASONS.map((reason) => <button className={`chip${comment === reason ? " is-active" : ""}`} type="button" key={reason} onClick={() => { setComment(reason); setLocalError(""); }}>{reason}</button>)}</div><textarea placeholder="请输入驳回原因" value={comment} disabled={loading} onChange={(event) => { setComment(event.currentTarget.value); setLocalError(""); }} />{localError || error ? <p className="form-error" role="alert">{localError || error}</p> : null}<div className="row sheet-log-hint"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16v.5" /></svg><span>首级驳回后，申请人可修改原单并重新提交</span></div><div className="btn-group approval-sheet__actions"><button className="btn btn--ghost" type="button" disabled={loading} onClick={onClose}>取消</button><button className="btn btn--danger btn--lg" type="button" disabled={loading} onClick={() => { const reason = comment.trim(); if (!reason) { setLocalError("请输入驳回原因（必填）"); return; } onSubmit({ comment: reason, ...(rejectToNodeId ? { rejectToNodeId } : {}) }, keyRef.current); }}>{loading ? "提交中..." : "确认驳回"}</button></div></div></div></div></>;
}

function createIdempotencyKey() { return typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `reject-${Date.now()}-${Math.random().toString(16).slice(2)}`; }

export default RejectSheet;
