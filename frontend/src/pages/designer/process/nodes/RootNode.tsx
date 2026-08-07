import { PlayCircleOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

export function RootNode({ node }: { node: TreeNode }) {
  const count = node.props?.assignedUser?.length ?? 0;
  return (
    <NodeCard
      node={node}
      kind="root"
      icon={<PlayCircleOutlined />}
      summary={count > 0 ? `指定 ${count} 人可发起` : '所有人可发起'}
      removable={false}
    />
  );
}
