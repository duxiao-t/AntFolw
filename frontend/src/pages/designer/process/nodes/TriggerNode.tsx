import { ApiOutlined } from '@ant-design/icons';
import type { TreeNode } from '../types';
import { NodeCard } from './NodeCard';

export function TriggerNode({ node }: { node: TreeNode }) {
  const props = node.props ?? {};
  let host = '未配置地址';
  try {
    host = new URL(props.url).host;
  } catch {
    host = '未配置地址';
  }
  return (
    <NodeCard
      node={node}
      kind="trigger"
      icon={<ApiOutlined />}
      summary={`${props.method ?? 'POST'} · ${host}`}
    />
  );
}
