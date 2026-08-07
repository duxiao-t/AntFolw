import type { TreeNode } from '../types';
import { BranchLanes } from './BranchLanes';

export function ConditionsNode({ node }: { node: TreeNode }) {
  return <BranchLanes node={node} parallel={false} />;
}
