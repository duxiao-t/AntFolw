import { CloseOutlined, ExclamationCircleFilled } from '@ant-design/icons';
import { Tooltip } from 'antd';
import { useNodeValidation } from '../ProcessValidationContext';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

export function NodeCard({
  node,
  kind,
  icon,
  summary,
  removable = true,
}: {
  node: TreeNode;
  kind: string;
  icon: React.ReactNode;
  summary: React.ReactNode;
  removable?: boolean;
}) {
  const select = useProcessDesignerStore((state) => state.select);
  const remove = useProcessDesignerStore((state) => state.removeNode);
  const issue = useNodeValidation(node.id);
  return (
    <button
      type="button"
      data-node-id={node.id}
      className={`pt-node pt-node--${kind}${issue ? ' pt-node--invalid' : ''}`}
      onClick={() => select(node.id)}
    >
      <span className="pt-node__title">
        <span className="pt-node__title-main">
          {icon}
          <span>{node.name}</span>
        </span>
        <span className="pt-node__actions">
          {issue && (
            <Tooltip title={issue.message}>
              <ExclamationCircleFilled className="pt-node__error" />
            </Tooltip>
          )}
          {removable && (
            <Tooltip title="删除节点">
              <CloseOutlined
                className="pt-node__del"
                onClick={(event) => {
                  event.stopPropagation();
                  remove(node.id);
                }}
              />
            </Tooltip>
          )}
        </span>
      </span>
      <span className="pt-node__body">{summary}</span>
    </button>
  );
}
