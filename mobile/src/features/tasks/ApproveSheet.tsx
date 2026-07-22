import { useEffect, useRef, useState } from 'react';
import { Button, TextArea } from 'antd-mobile';
import type { CSSProperties } from 'react';
import { useFocusTrap } from '../../shared/a11y/useFocusTrap';

const overlayStyle: CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,0.45)',
  display: 'grid',
  alignItems: 'end',
  zIndex: 1000,
};

const backdropStyle: CSSProperties = {
  position: 'absolute',
  inset: 0,
  border: 0,
  padding: 0,
  margin: 0,
  background: 'transparent',
  cursor: 'pointer',
};

const panelWrapStyle: CSSProperties = {
  position: 'relative',
  zIndex: 1,
};

const panelStyle: CSSProperties = {
  background: 'var(--af-color-bg)',
  borderRadius: '16px 16px 0 0',
  padding: '16px 16px calc(16px + env(safe-area-inset-bottom))',
  display: 'grid',
  gap: 12,
};

const errorStyle: CSSProperties = {
  color: 'var(--af-color-danger)',
  margin: 0,
};

export type ApproveSheetProps = {
  open: boolean;
  loading: boolean;
  error?: string;
  onClose: () => void;
  onSubmit: (payload: { comment?: string }, idempotencyKey: string) => void;
};

export function ApproveSheet({ open, loading, error, onClose, onSubmit }: ApproveSheetProps) {
  const [comment, setComment] = useState('');
  const keyRef = useRef(createIdempotencyKey());
  const panelRef = useRef<HTMLDivElement>(null);
  useFocusTrap(open, panelRef, onClose);

  useEffect(() => {
    if (open) {
      setComment('');
      keyRef.current = createIdempotencyKey();
    }
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div style={overlayStyle}>
      <button type="button" aria-label="关闭" style={backdropStyle} onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="同意审批"
        style={{ ...panelStyle, ...panelWrapStyle }}
      >
        <strong>同意</strong>
        <TextArea
          placeholder="可选填写审批意见"
          value={comment}
          rows={3}
          onChange={setComment}
          disabled={loading}
        />
        {error ? <p style={errorStyle}>{error}</p> : null}
        <Button
          block
          color="primary"
          loading={loading}
          disabled={loading}
          onClick={() => onSubmit({ comment: comment.trim() || undefined }, keyRef.current)}
        >
          确认同意
        </Button>
        <Button block fill="outline" disabled={loading} onClick={onClose}>
          取消
        </Button>
      </div>
    </div>
  );
}

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `approve-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default ApproveSheet;
