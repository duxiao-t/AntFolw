import type { TreeNode } from '../types';
import { BranchLanes } from './BranchLanes';

export function ParallelNode({ node }: { node: TreeNode }) {
  return <BranchLanes node={node} parallel />;
}
