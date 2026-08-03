import { CloseOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function CcNode({ node }: { node: TreeNode }) {
  const select = useProcessDesignerStore((s) => s.select);
  const remove = useProcessDesignerStore((s) => s.removeNode);
  const p = node.props ?? {};
  const count = p.assignedUser?.length ?? 0;
  return (
    <div className="pt-node pt-node--cc" onClick={() => select(node.id)}>
      <div className="pt-node__title">
        <span>{node.name || '抄送人'}</span>
        <CloseOutlined
          className="pt-node__del"
          onClick={(e) => {
            e.stopPropagation();
            remove(node.id);
          }}
        />
      </div>
      <div className="pt-node__body">抄送 {count} 人</div>
    </div>
  );
}