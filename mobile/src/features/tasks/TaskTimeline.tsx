import type { CSSProperties } from 'react';
import type { MobileHistoryItem } from './tasks.api';

const listStyle: CSSProperties = {
  display: 'grid',
  gap: 10,
  margin: 0,
  padding: 0,
  listStyle: 'none',
};

const itemStyle: CSSProperties = {
  display: 'grid',
  gap: 4,
  padding: '10px 12px',
  borderRadius: 8,
  border: '1px solid var(--af-color-border)',
  background: 'var(--af-color-surface)',
};

const metaStyle: CSSProperties = {
  color: 'rgba(0,0,0,0.55)',
  fontSize: '0.8125rem',
};

export function TaskTimeline({
  history,
  processSnapshot,
}: {
  history: MobileHistoryItem[];
  processSnapshot: unknown;
}) {
  if (history.length === 0) {
    return <p style={metaStyle}>暂无流转记录</p>;
  }

  return (
    <ol style={listStyle}>
      {history.map((entry) => (
        <li key={entry.id} style={itemStyle}>
          <strong>{actionLabel(entry.action)}</strong>
          <span style={metaStyle}>
            {nodeLabel(processSnapshot, entry.fromNodeId)} → {nodeLabel(processSnapshot, entry.toNodeId)}
          </span>
          {entry.comment ? <span>{entry.comment}</span> : null}
          <span style={metaStyle}>{formatDate(entry.createdAt)}</span>
        </li>
      ))}
    </ol>
  );
}

function actionLabel(action: string): string {
  switch (action) {
    case 'START':
      return '发起';
    case 'ARRIVE':
      return '到达';
    case 'APPROVE':
      return '同意';
    case 'REJECT':
      return '驳回';
    case 'WITHDRAW':
      return '撤回';
    case 'COMPLETE':
      return '完成';
    case 'SKIP':
      return '跳过';
    default:
      return action;
  }
}

function nodeLabel(snapshot: unknown, nodeId?: string | null): string {
  if (!nodeId) {
    return '—';
  }
  const name = findNodeName(snapshot, nodeId);
  return name ?? nodeId;
}

function findNodeName(node: unknown, nodeId: string): string | null {
  if (!node || typeof node !== 'object') {
    return null;
  }
  const record = node as Record<string, unknown>;
  if (record.id === nodeId) {
    const props = record.props as Record<string, unknown> | undefined;
    const fromProps = typeof props?.name === 'string' ? props.name : null;
    const fromLabel = typeof record.label === 'string' ? record.label : null;
    const fromName = typeof record.name === 'string' ? record.name : null;
    return fromProps ?? fromLabel ?? fromName ?? nodeId;
  }
  for (const value of Object.values(record)) {
    if (Array.isArray(value)) {
      for (const child of value) {
        const found = findNodeName(child, nodeId);
        if (found) return found;
      }
    } else if (value && typeof value === 'object') {
      const found = findNodeName(value, nodeId);
      if (found) return found;
    }
  }
  return null;
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

export default TaskTimeline;
