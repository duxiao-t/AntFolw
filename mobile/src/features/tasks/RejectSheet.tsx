import { useEffect, useRef, useState } from 'react';
import { Button, TextArea } from 'antd-mobile';
import type { CSSProperties } from 'react';
import type { RejectTarget } from './tasks.api';

const overlayStyle: CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,0.45)',
  display: 'grid',
  alignItems: 'end',
  zIndex: 1000,
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

const selectStyle: CSSProperties = {
  minHeight: 40,
  borderRadius: 8,
  border: '1px solid var(--af-color-border)',
  padding: '8px 10px',
  font: 'inherit',
  background: 'var(--af-color-surface)',
};

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
  const [comment, setComment] = useState('');
  const [rejectToNodeId, setRejectToNodeId] = useState('');
  const [localError, setLocalError] = useState('');
  const keyRef = useRef(createIdempotencyKey());

  useEffect(() => {
    if (open) {
      setComment('');
      setRejectToNodeId(rejectTargets[0]?.nodeId ?? '');
      setLocalError('');
      keyRef.current = createIdempotencyKey();
    }
  }, [open, rejectTargets]);

  if (!open) {
    return null;
  }

  return (
    <div style={overlayStyle} role="presentation" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label="驳回审批"
        style={panelStyle}
        onClick={(event) => event.stopPropagation()}
      >
        <strong>驳回</strong>
        <TextArea
          placeholder="请填写驳回原因"
          value={comment}
          rows={3}
          onChange={setComment}
          disabled={loading}
        />
        {rejectTargets.length > 0 ? (
          <label style={{ display: 'grid', gap: 6 }}>
            <span>驳回至</span>
            <select
              style={selectStyle}
              value={rejectToNodeId}
              disabled={loading}
              onChange={(event) => setRejectToNodeId(event.target.value)}
            >
              {rejectTargets.map((target) => (
                <option key={target.nodeId} value={target.nodeId}>
                  {target.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {localError || error ? <p style={errorStyle}>{localError || error}</p> : null}
        <Button
          block
          color="danger"
          loading={loading}
          disabled={loading}
          onClick={() => {
            const trimmed = comment.trim();
            if (!trimmed) {
              setLocalError('请填写驳回原因');
              return;
            }
            setLocalError('');
            onSubmit(
              {
                comment: trimmed,
                rejectToNodeId: rejectToNodeId || undefined,
              },
              keyRef.current,
            );
          }}
        >
          确认驳回
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
  return `reject-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default RejectSheet;
