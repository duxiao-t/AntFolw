import { Button } from 'antd';
import { NodeChain } from '../NodeChain';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function ConditionsNode({ node }: { node: TreeNode }) {
  const select = useProcessDesignerStore((s) => s.select);
  const addBranch = useProcessDesignerStore((s) => s.addBranch);
  const insert = useProcessDesignerStore((s) => s.insertAfter);
  return (
    <div className="pt-conditions">
      <div className="pt-conditions__head">
        <Button size="small" onClick={() => addBranch(node.id)}>
          + 条件
        </Button>
      </div>
      <div className="pt-branches">
        {node.branchs?.map((b) => (
          <div className="pt-branch" key={b.id}>
            <div
              className="pt-branch__title"
              onClick={() => select(b.id)}
            >
              {b.name}
              {b.props?.isDefault ? '（默认）' : ''}
            </div>
            {b.children ? <NodeChain node={b.children} /> : null}
            <div className="pt-add pt-add--branch">
              <Button size="small" onClick={() => insert(b.id, 'APPROVAL')}>
                + 审批
              </Button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}