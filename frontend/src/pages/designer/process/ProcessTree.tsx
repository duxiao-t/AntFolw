import './process-tree.less';
import { NodeChain } from './NodeChain';
import { useProcessDesignerStore } from './useProcessDesignerStore';

export function ProcessTree() {
  const process = useProcessDesignerStore((s) => s.process);
  return (
    <div className="pt-root">
      <NodeChain node={process} />
      <div className="pt-end">流程结束</div>
    </div>
  );
}