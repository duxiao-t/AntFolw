import { createContext, useContext } from 'react';
import type { ProcessValidationIssue } from './validation';

export const ProcessValidationContext = createContext<ProcessValidationIssue[]>(
  [],
);

export function useNodeValidation(
  nodeId: string,
): ProcessValidationIssue | undefined {
  return useContext(ProcessValidationContext).find(
    (issue) => issue.nodeId === nodeId,
  );
}
