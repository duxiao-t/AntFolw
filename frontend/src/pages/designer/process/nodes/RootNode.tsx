import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function RootNode({ node }: { node: TreeNode }) {
  const select = useProcessDesignerStore((s) => s.select);
  return (
    <div
      className="pt-node pt-node--root"
      onClick={() => select(node.id)}
    >
      <div className="pt-node__title">
        <span>{node.name || '发起人'}</span>
      </div>
      <div className="pt-node__body">所有人可发起</div>
    </div>
  );
}