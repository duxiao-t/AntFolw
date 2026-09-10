import { UserSwitchOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

export function ApprovalNode({ node }: { node: TreeNode }) {
  const props = node.props ?? {};
  const modeLabel = {
    OR: '或签',
    ANY: '或签',
    AND: '会签',
    ALL: '会签',
    RATIO: '比例签',
  }[props.mode as string] ?? '审批方式待配置';
  const summary =
    props.assignedType === 'ASSIGN_USER'
      ? `指定成员 ${props.assignedUser?.length ?? 0} 人`
      : props.assignedType === 'ROLE'
        ? `角色 ${props.role?.length ?? 0} 个`
        : props.assignedType === 'LEADER'
          ? `第 ${props.leader?.level ?? 1} 级部门主管`
          : props.assignedType === 'DIRECT_MANAGER'
            ? `制单人的第 ${props.manager?.level ?? 1} 级直属上级`
            : props.assignedType === 'SELF'
              ? '发起人本人'
              : props.assignedType === 'FIELD_USER'
                ? '表单中的人员'
                : '发起人自选';
  const fallback = props.fallbackAssignee;
  const fallbackSummary = fallback?.type === 'ROLE'
    ? `兜底角色 ${fallback.ids?.length ?? 0} 个`
    : fallback?.type === 'USER'
      ? `兜底成员 ${fallback.ids?.length ?? 0} 人`
      : '兜底待配置';
  return (
    <NodeCard
      node={node}
      kind="approval"
      icon={<UserSwitchOutlined />}
      summary={`${summary} · ${modeLabel} · ${fallbackSummary}`}
    />
  );
}
