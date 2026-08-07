import { ClockCircleOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

const UNIT_LABEL: Record<string, string> = {
  MINUTES: '分钟',
  HOURS: '小时',
  DAYS: '天',
};

export function DelayNode({ node }: { node: TreeNode }) {
  const props = node.props ?? {};
  const summary =
    props.mode === 'UNTIL_TIME'
      ? `等待至当天 ${props.time ?? '--:--'}`
      : `等待 ${props.amount ?? 0} ${UNIT_LABEL[props.unit] ?? ''}`;
  return (
    <NodeCard
      node={node}
      kind="delay"
      icon={<ClockCircleOutlined />}
      summary={summary}
    />
  );
}
