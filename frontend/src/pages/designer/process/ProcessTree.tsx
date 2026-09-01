import { useMemo } from 'react';
import './process-tree.less';
import { NodeChain } from './NodeChain';
import { ProcessValidationContext } from './ProcessValidationContext';
import { useProcessDesignerStore } from './useProcessDesignerStore';
import { validateProcessTree } from './validation';
import type { FormFieldOption } from './types';

export function ProcessTree({
  zoom = 100,
  formFields,
}: {
  zoom?: number;
  formFields?: FormFieldOption[];
}) {
  const process = useProcessDesignerStore((state) => state.process);
  const issues = useMemo(
    () => validateProcessTree(process, formFields),
    [process, formFields],
  );
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
