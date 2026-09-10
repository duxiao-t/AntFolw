import { SendOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

export function CcNode({ node }: { node: TreeNode }) {
  const props = node.props ?? {};
  const type = props.assignedType ?? 'ASSIGN_USER';
  const summary = type === 'ROLE'
    ? `抄送角色 ${props.role?.length ?? 0} 个`
    : type === 'FIELD_USER'
      ? '抄送表单中的人员'
      : `抄送成员 ${props.assignedUser?.length ?? 0} 人`;
  return (
    <NodeCard
      node={node}
      kind="cc"
      icon={<SendOutlined />}
      summary={summary}
    />
  );
}
