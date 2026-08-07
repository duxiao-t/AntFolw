import { SendOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

export function CcNode({ node }: { node: TreeNode }) {
  const count = node.props?.assignedUser?.length ?? 0;
  return (
    <NodeCard
      node={node}
      kind="cc"
      icon={<SendOutlined />}
      summary={`抄送 ${count} 人`}
    />
  );
}
