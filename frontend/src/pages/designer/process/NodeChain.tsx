import {
  ApiOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  ForkOutlined,
  PlusOutlined,
  SendOutlined,
  UserSwitchOutlined,
} from '@ant-design/icons';
import { Popover } from 'antd';
import { ApprovalNode } from './nodes/ApprovalNode';
import { CcNode } from './nodes/CcNode';
import { ConditionsNode } from './nodes/ConditionsNode';
import { DelayNode } from './nodes/DelayNode';
import { ParallelNode } from './nodes/ParallelNode';
import { RootNode } from './nodes/RootNode';
import { TriggerNode } from './nodes/TriggerNode';
import type { DesignerNodeType, TreeNode } from './types';
import { useProcessDesignerStore } from './useProcessDesignerStore';

const MENU_ITEMS: Array<{
  type: DesignerNodeType;
  label: string;
  icon: React.ReactNode;
}> = [
  { type: 'APPROVAL', label: '审批', icon: <UserSwitchOutlined /> },
  { type: 'CC', label: '抄送', icon: <SendOutlined /> },
  { type: 'CONDITIONS', label: '条件', icon: <BranchesOutlined /> },
  { type: 'PARALLEL', label: '并行', icon: <ForkOutlined /> },
  { type: 'DELAY', label: '延时', icon: <ClockCircleOutlined /> },
  { type: 'TRIGGER', label: '触发器', icon: <ApiOutlined /> },
];

export function AddButton({
  parentId,
  parallel = false,
}: {
  parentId: string;
  parallel?: boolean;
}) {
  const insert = useProcessDesignerStore((state) => state.insertAfter);
  const items = MENU_ITEMS;
  const menu = (
    <div className="pt-insert-menu">
      {items.map((item) => (
        <button
          className="pt-insert-menu__item"
          key={item.type}
          type="button"
          onClick={() => insert(parentId, item.type)}
        >
          <span
            className={`pt-insert-menu__icon pt-insert-menu__icon--${item.type.toLowerCase()}`}
          >
            {item.icon}
          </span>
          <span>{item.label}</span>
        </button>
      ))}
    </div>
  );
  return (
    <div className="pt-add">
      <Popover content={menu} trigger="click" placement="right">
        <button type="button" className="pt-add__btn" aria-label="添加流程节点">
          <PlusOutlined />
        </button>
      </Popover>
    </div>
  );
}

export function NodeChain({
  node,
  parallel = false,
}: {
  node: TreeNode;
  parallel?: boolean;
}) {
  let card: React.ReactNode = null;
  if (node.type === 'ROOT') card = <RootNode node={node} />;
  else if (node.type === 'APPROVAL') card = <ApprovalNode node={node} />;
  else if (node.type === 'CC') card = <CcNode node={node} />;
  else if (node.type === 'CONDITIONS') card = <ConditionsNode node={node} />;
  else if (node.type === 'PARALLEL') card = <ParallelNode node={node} />;
  else if (node.type === 'DELAY') card = <DelayNode node={node} />;
  else if (node.type === 'TRIGGER') card = <TriggerNode node={node} />;

  return (
    <div className="pt-chain">
      {card}
      {node.type !== 'CONDITIONS' && node.type !== 'PARALLEL' && (
        <AddButton parentId={node.id} parallel={parallel} />
      )}
      {node.children ? (
        <NodeChain node={node.children} parallel={parallel} />
      ) : null}
    </div>
  );
}
