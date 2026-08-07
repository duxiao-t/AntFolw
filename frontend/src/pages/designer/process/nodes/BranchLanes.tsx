import {
  CopyOutlined,
  DeleteOutlined,
  ExclamationCircleFilled,
  LeftOutlined,
  PlusOutlined,
  RightOutlined,
} from '@ant-design/icons';
import { Tooltip } from 'antd';
import { AddButton, NodeChain } from '../NodeChain';
import { useNodeValidation } from '../ProcessValidationContext';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function BranchLanes({
  node,
  parallel,
}: {
  node: TreeNode;
  parallel: boolean;
}) {
  const select = useProcessDesignerStore((state) => state.select);
  const addBranch = useProcessDesignerStore((state) => state.addBranch);
  const copyBranch = useProcessDesignerStore((state) => state.copyBranch);
  const removeBranch = useProcessDesignerStore((state) => state.removeBranch);
  const moveBranch = useProcessDesignerStore((state) => state.moveBranch);
  const ownerIssue = useNodeValidation(node.id);
  const branches = node.branchs ?? [];

  return (
    <div
      className={`pt-band${ownerIssue ? ' pt-band--invalid' : ''}`}
      data-node-id={node.id}
    >
      {!parallel && (
        <button
          type="button"
          className="pt-band__gateway"
          onClick={() => select(node.id)}
        >
          条件分支
          {ownerIssue && (
            <Tooltip title={ownerIssue.message}>
              <ExclamationCircleFilled />
            </Tooltip>
          )}
        </button>
      )}
      <button
        type="button"
        className={`pt-band__add${parallel ? ' pt-band__add--parallel' : ''}`}
        disabled={branches.length >= 8}
        onClick={() => addBranch(node.id)}
      >
        <PlusOutlined /> {parallel ? '添加分支' : '添加条件'}
        {parallel && ownerIssue && (
          <Tooltip title={ownerIssue.message}>
            <ExclamationCircleFilled />
          </Tooltip>
        )}
      </button>
      <div className="pt-band__inner">
        <div className="pt-band__branches">
          {branches.map((branch, index) => (
            <BranchLane
              key={branch.id}
              owner={node}
              branch={branch}
              index={index}
              parallel={parallel}
              onSelect={() => select(branch.id)}
              onCopy={() => copyBranch(node.id, branch.id)}
              onRemove={() => removeBranch(node.id, branch.id)}
              onMove={(direction) => moveBranch(node.id, branch.id, direction)}
            />
          ))}
        </div>
        <div className="pt-band__merge" aria-hidden="true" />
      </div>
    </div>
  );
}

function BranchLane({
  owner,
  branch,
  index,
  parallel,
  onSelect,
  onCopy,
  onRemove,
  onMove,
}: {
  owner: TreeNode;
  branch: TreeNode;
  index: number;
  parallel: boolean;
  onSelect: () => void;
  onCopy: () => void;
  onRemove: () => void;
  onMove: (direction: -1 | 1) => void;
}) {
  const issue = useNodeValidation(branch.id);
  const count = owner.branchs?.length ?? 0;
  const isDefault = branch.props?.isDefault === true;
  const lastMovableIndex = parallel ? count - 1 : count - 2;
  return (
    <div className="pt-band__branch" data-node-id={branch.id}>
      <div className="pt-band__connector pt-band__connector--top" />
      <div className={`pt-branch${issue ? ' pt-branch--invalid' : ''}`}>
        <button type="button" className="pt-branch__main" onClick={onSelect}>
          <span className="pt-branch__name">{branch.name || '未命名分支'}</span>
          <span className="pt-branch__meta">
            {isDefault
              ? '默认分支'
              : parallel
                ? branch.props?.conditionMode === 'WHEN_MATCHED'
                  ? '满足条件时执行'
                  : '始终执行'
                : `优先级 ${index + 1}`}
          </span>
        </button>
        <span className="pt-branch__actions">
          {issue && (
            <Tooltip title={issue.message}>
              <ExclamationCircleFilled className="pt-branch__error" />
            </Tooltip>
          )}
          <Tooltip title="左移">
            <button
              type="button"
              aria-label="左移分支"
              disabled={isDefault || index === 0}
              onClick={() => onMove(-1)}
            >
              <LeftOutlined />
            </button>
          </Tooltip>
          <Tooltip title="右移">
            <button
              type="button"
              aria-label="右移分支"
              disabled={isDefault || index >= lastMovableIndex}
              onClick={() => onMove(1)}
            >
              <RightOutlined />
            </button>
          </Tooltip>
          <Tooltip title="复制分支">
            <button
              type="button"
              aria-label="复制分支"
              disabled={isDefault || count >= 8}
              onClick={onCopy}
            >
              <CopyOutlined />
            </button>
          </Tooltip>
          <Tooltip title="删除分支">
            <button
              type="button"
              aria-label="删除分支"
              disabled={isDefault || count < 2}
              onClick={onRemove}
            >
              <DeleteOutlined />
            </button>
          </Tooltip>
        </span>
      </div>
      <AddButton parentId={branch.id} parallel={parallel} />
      {branch.children && (
        <NodeChain node={branch.children} parallel={parallel} />
      )}
      <div className="pt-band__connector pt-band__connector--bottom" />
    </div>
  );
}
