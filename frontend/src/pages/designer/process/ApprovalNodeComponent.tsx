import { Handle, Position } from '@xyflow/react';
import type { NodeProps, Node } from '@xyflow/react';

export type ApprovalFlowNodeData = {
  label: string;
  assignee: { type: string; ids: (string | number)[] };
  errorCount?: number;
};

export function ApprovalNode({ data, selected }: NodeProps<Node<ApprovalFlowNodeData, 'approval'>>) {
  return (
    <div
      style={{
        padding: 12,
        minWidth: 160,
        border: `2px solid ${
          data.errorCount
            ? '#ff4d4f'
            : selected
              ? '#1677ff'
              : '#888'
        }`,
        borderRadius: 6,
        background: '#fff',
      }}
    >
      <Handle type="target" position={Position.Top} />
      <div style={{ fontWeight: 600 }}>{data.label}</div>
      <small>
        {data.assignee?.type} · {(data.assignee?.ids ?? []).length} 人
      </small>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

export function StartNode() {
  return (
    <div
      style={{
        padding: 12,
        minWidth: 120,
        border: '2px solid #52c41a',
        borderRadius: 6,
        background: '#f6ffed',
        textAlign: 'center',
      }}
    >
      <Handle type="source" position={Position.Bottom} />
      开始
    </div>
  );
}

export function EndNode() {
  return (
    <div
      style={{
        padding: 12,
        minWidth: 120,
        border: '2px solid #f5222d',
        borderRadius: 6,
        background: '#fff1f0',
        textAlign: 'center',
      }}
    >
      <Handle type="target" position={Position.Top} />
      结束
    </div>
  );
}

export const processNodeTypes = {
  start: StartNode,
  approval: ApprovalNode,
  end: EndNode,
};
