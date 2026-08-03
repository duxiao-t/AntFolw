import { CloseOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function ApprovalNode({ node }: { node: TreeNode }) {
  const select = useProcessDesignerStore((s) => s.select);
  const remove = useProcessDesignerStore((s) => s.removeNode);
  const p = node.props ?? {};
  const summary =
    p.assignedType === 'ASSIGN_USER'
      ? `指定成员 ${p.assignedUser?.length ?? 0} 人`
      : p.assignedType === 'ROLE'
        ? `角色 ${p.role?.length ?? 0} 个`
        : p.assignedType === 'LEADER'
          ? `第 ${p.leader?.level ?? 1} 级主管`
          : p.assignedType === 'SELF'
            ? '发起人本人'
            : '发起人自选';
  return (
    <div
      className="pt-node pt-node--approval"
      onClick={() => select(node.id)}
    >
      <div className="pt-node__title">
        <span>{node.name || '审批人'}</span>
        <CloseOutlined
          className="pt-node__del"
          onClick={(e) => {
            e.stopPropagation();
            remove(node.id);
          }}
        />
      </div>
      <div className="pt-node__body">
        {summary} · {p.mode === 'AND' ? '会签' : '或签'}
      </div>
    </div>
  );
}