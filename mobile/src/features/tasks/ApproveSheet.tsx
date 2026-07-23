import { useEffect, useRef, useState } from "react";
import { useFocusTrap } from "../../shared/a11y/useFocusTrap";

export type ApproveSheetProps = {
  open: boolean;
  loading: boolean;
  error?: string;
  onClose: () => void;
  onSubmit: (payload: { comment?: string }, idempotencyKey: string) => void;
};

export function ApproveSheet({ open, loading, error, onClose, onSubmit }: ApproveSheetProps) {
  const [comment, setComment] = useState("");
  const keyRef = useRef(createIdempotencyKey());
  const panelRef = useRef<HTMLDivElement>(null);
  useFocusTrap(open, panelRef, onClose);

  useEffect(() => {
    if (open) {
      setComment("");
      keyRef.current = createIdempotencyKey();
    }
  }, [open]);

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
        aria-label="同意审批"
        className="af-sheet__panel"
      >
        <h3>同意申请</h3>
        <textarea
          className="af-sheet__textarea"
          placeholder="填写审批意见（选填）"
          value={comment}
          disabled={loading}
          onChange={(event) => setComment(event.currentTarget.value)}
        />
        {error ? (
          <p style={{ color: "var(--af-color-danger)", fontSize: 11, margin: "6px 0 0" }}>
            {error}
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
            className="af-btn"
            disabled={loading}
            onClick={() => onSubmit({ comment: comment.trim() || undefined }, keyRef.current)}
          >
            {loading ? "提交中..." : "确认同意"}
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
  return `approve-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default ApproveSheet;
