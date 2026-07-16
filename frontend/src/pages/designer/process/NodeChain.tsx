import { Button, Popover } from 'antd';
import type { TreeNode } from './types';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { RootNode } from './nodes/RootNode';
import { ApprovalNode } from './nodes/ApprovalNode';
import { CcNode } from './nodes/CcNode';
import { ConditionsNode } from './nodes/ConditionsNode';

function AddButton({ parentId }: { parentId: string }) {
  const insert = useProcessDesignerStore((s) => s.insertAfter);
  const menu = (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, width: 120 }}>
      <Button size="small" block onClick={() => insert(parentId, 'APPROVAL')}>
        审批人
      </Button>
      <Button size="small" block onClick={() => insert(parentId, 'CC')}>
        抄送人
      </Button>
      <Button
        size="small"
        block
        onClick={() => insert(parentId, 'CONDITIONS')}
      >
        条件分支
      </Button>
    </div>
  );
  return (
    <div className="pt-add">
      <Popover content={menu} trigger="click" placement="right">
        <button type="button" className="pt-add__btn">
          +
        </button>
      </Popover>
    </div>
  );
}

export function NodeChain({ node }: { node: TreeNode }) {
  let card: React.ReactNode = null;
  if (node.type === 'ROOT') card = <RootNode node={node} />;
  else if (node.type === 'APPROVAL') card = <ApprovalNode node={node} />;
  else if (node.type === 'CC') card = <CcNode node={node} />;
  else if (node.type === 'CONDITIONS') card = <ConditionsNode node={node} />;

  return (
    <div className="pt-chain">
      {card}
      {node.type !== 'CONDITIONS' && <AddButton parentId={node.id} />}
      {node.children ? <NodeChain node={node.children} /> : null}
    </div>
  );
}