import { useEffect, useRef, useState } from "react";
import { useFocusTrap } from "../../shared/a11y/useFocusTrap";

export function ApproveSheet({ open, loading, error, onClose, onSubmit }: { open: boolean; loading: boolean; error?: string; onClose: () => void; onSubmit: (payload: { comment?: string }, idempotencyKey: string) => void }) {
  const [comment, setComment] = useState("");
  const keyRef = useRef(createIdempotencyKey());
  const panelRef = useRef<HTMLDivElement>(null);
  useFocusTrap(open, panelRef, onClose);
  useEffect(() => { if (open) { setComment(""); keyRef.current = createIdempotencyKey(); } }, [open]);
  if (!open) return null;
  return <><button type="button" className="sheet-mask is-open" aria-label="关闭" onClick={onClose} /><div className="sheet is-open" ref={panelRef} role="dialog" aria-modal="true" aria-label="同意审批"><div className="sheet__inner"><div className="sheet__title"><h3>同意本次申请</h3><span className="chip chip--success-soft" style={{ marginLeft: "auto" }}>通过</span></div><p className="muted small approval-sheet__hint">填写审批意见后，该单据将继续流转。</p><div className="sheet__form"><textarea placeholder="请输入审批意见" value={comment} disabled={loading} onChange={(event) => setComment(event.currentTarget.value)} />{error ? <p className="form-error" role="alert">{error}</p> : null}<div className="row sheet-log-hint"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16v.5" /></svg><span>审批意见将同步到操作日志</span></div><div className="btn-group approval-sheet__actions"><button className="btn btn--ghost" type="button" disabled={loading} onClick={onClose}>取消</button><button className="btn btn--success btn--lg" type="button" disabled={loading} onClick={() => onSubmit({ comment: comment.trim() || undefined }, keyRef.current)}>{loading ? "提交中..." : "确认同意"}</button></div></div></div></div></>;
}

function createIdempotencyKey() { return typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `approve-${Date.now()}-${Math.random().toString(16).slice(2)}`; }

export default ApproveSheet;
