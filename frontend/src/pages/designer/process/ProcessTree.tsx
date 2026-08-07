import { useMemo } from 'react';
import './process-tree.less';
import { NodeChain } from './NodeChain';
import { ProcessValidationContext } from './ProcessValidationContext';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { validateProcessTree } from './validation';

export function ProcessTree({ zoom = 100 }: { zoom?: number }) {
  const process = useProcessDesignerStore((state) => state.process);
  const issues = useMemo(() => validateProcessTree(process), [process]);
  return (
    <ProcessValidationContext.Provider value={issues}>
      <div className="pt-canvas" style={{ zoom: zoom / 100 }}>
        <div className="pt-root">
          <NodeChain node={process} />
          <div className="pt-end">流程结束</div>
        </div>
      </div>
    </ProcessValidationContext.Provider>
  );
}
