import { UserSwitchOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

export function ApprovalNode({ node }: { node: TreeNode }) {
  const props = node.props ?? {};
  const summary =
    props.assignedType === 'ASSIGN_USER'
      ? `指定成员 ${props.assignedUser?.length ?? 0} 人`
      : props.assignedType === 'ROLE'
        ? `角色 ${props.role?.length ?? 0} 个`
        : props.assignedType === 'LEADER'
          ? `第 ${props.leader?.level ?? 1} 级主管`
          : props.assignedType === 'SELF'
            ? '发起人本人'
            : '发起人自选';
  return (
    <NodeCard
      node={node}
      kind="approval"
      icon={<UserSwitchOutlined />}
      summary={`${summary} · ${props.mode === 'AND' ? '会签' : '或签'}`}
    />
  );
}
