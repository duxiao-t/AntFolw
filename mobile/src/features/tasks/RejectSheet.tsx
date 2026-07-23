import { useEffect, useRef, useState } from "react";
import { useFocusTrap } from "../../shared/a11y/useFocusTrap";
import type { RejectTarget } from "./tasks.api";

export type RejectSheetProps = {
  open: boolean;
  loading: boolean;
  error?: string;
  rejectTargets: RejectTarget[];
  onClose: () => void;
  onSubmit: (payload: { comment: string; rejectToNodeId?: string }, idempotencyKey: string) => void;
};

export function RejectSheet({
  open,
  loading,
  error,
  rejectTargets,
  onClose,
  onSubmit,
}: RejectSheetProps) {
  const [comment, setComment] = useState("");
  const [rejectToNodeId, setRejectToNodeId] = useState("");
  const [localError, setLocalError] = useState("");
  const keyRef = useRef(createIdempotencyKey());
  const panelRef = useRef<HTMLDivElement>(null);
  useFocusTrap(open, panelRef, onClose);

  useEffect(() => {
    if (open) {
      setComment("");
      setRejectToNodeId(rejectTargets[0]?.nodeId ?? "");
      setLocalError("");
      keyRef.current = createIdempotencyKey();
    }
  }, [open, rejectTargets]);

  if (!open) {
    return null;
  }

  return (
    <div className="af-sheet" role="presentation">
      <button type="button" className="af-sheet__backdrop" aria-label="关闭" onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="驳回审批"
        className="af-sheet__panel"
      >
        <h3>驳回申请</h3>
        {rejectTargets.length > 0 ? (
          <label style={{ display: "grid", gap: 6, marginBottom: 10 }}>
            <span style={{ fontSize: 11, color: "var(--af-color-muted)" }}>驳回到</span>
            <select
              className="af-input"
              value={rejectToNodeId}
              disabled={loading}
              onChange={(event) => setRejectToNodeId(event.target.value)}
              style={{ height: 36 }}
            >
              {rejectTargets.map((target) => (
                <option key={target.nodeId} value={target.nodeId}>
                  {target.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <textarea
          className="af-sheet__textarea"
          placeholder="请输入驳回原因（必填）"
          value={comment}
          disabled={loading}
          onChange={(event) => setComment(event.currentTarget.value)}
        />
        {localError || error ? (
          <p style={{ color: "var(--af-color-danger)", fontSize: 11, margin: "6px 0 0" }}>
            {localError || error}
          </p>
        ) : null}
        <div className="af-sheet__buttons">
          <button
            type="button"
            className="af-btn af-btn--ghost"
            disabled={loading}
            onClick={onClose}
          >
            取消
          </button>
          <button
            type="button"
            className="af-btn af-btn--danger-solid"
            disabled={loading}
            onClick={() => {
              const trimmed = comment.trim();
              if (!trimmed) {
                setLocalError("请填写驳回原因");
                return;
              }
              setLocalError("");
              onSubmit(
                {
                  comment: trimmed,
                  rejectToNodeId: rejectToNodeId || undefined,
                },
                keyRef.current,
              );
            }}
          >
            {loading ? "提交中..." : "确认驳回"}
          </button>
        </div>
      </div>
    </div>
  );
}

function createIdempotencyKey() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `reject-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default RejectSheet;
