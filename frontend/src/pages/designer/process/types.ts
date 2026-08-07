export type NodeType =
  | 'ROOT'
  | 'APPROVAL'
  | 'CC'
  | 'CONDITIONS'
  | 'CONDITION'
  | 'PARALLEL'
  | 'BRANCH'
  | 'DELAY'
  | 'TRIGGER'
  | 'EMPTY';

export type TreeNode = {
  id: string;
  parentId?: string;
  type: NodeType;
  name?: string;
  props?: Record<string, any>;
  children?: TreeNode | null;
  branchs?: TreeNode[];
};

export type DesignerNodeType =
  | 'APPROVAL'
  | 'CC'
  | 'CONDITIONS'
  | 'PARALLEL'
  | 'DELAY'
  | 'TRIGGER';

export type ConditionOperator =
  | '=='
  | '!='
  | '>'
  | '>='
  | '<'
  | '<='
  | 'in'
  | 'contains';

export type ProcessCondition = {
  id: string;
  field: string;
  operator: ConditionOperator;
  value: string | string[];
};

export type ProcessConditionGroup = {
  id?: string;
  groupType: 'OR' | 'AND';
  conditions: ProcessCondition[];
};

export type ProcessConditionProps = {
  isDefault?: boolean;
  conditionMode?: 'ALWAYS' | 'WHEN_MATCHED';
  groupsType?: 'OR' | 'AND';
  groups?: ProcessConditionGroup[];
};

export const APPROVAL_PROPS = () => ({
  assignedType: 'ASSIGN_USER',
  mode: 'OR',
  assignedUser: [] as number[],
  role: [] as number[],
  leader: { level: 1 },
  selfSelect: { multiple: false },
  nobody: { handler: 'TO_PASS' },
});

export const CC_PROPS = () => ({
  assignedUser: [] as number[],
  role: [] as number[],
});

export const CONDITION_PROPS = () => ({
  isDefault: false,
  groupsType: 'OR' as const,
  groups: [{ groupType: 'AND' as const, conditions: [] as ProcessCondition[] }],
});

export const BRANCH_PROPS = () => ({
  conditionMode: 'ALWAYS' as const,
  groupsType: 'OR' as const,
  groups: [{ groupType: 'AND' as const, conditions: [] as ProcessCondition[] }],
});

export const DELAY_PROPS = () => ({
  mode: 'DURATION',
  amount: 1,
  unit: 'HOURS',
  time: '09:00',
});

export const TRIGGER_PROPS = () => ({
  method: 'POST',
  url: '',
  contentType: 'application/json',
  headers: [] as Array<{ id: string; key: string; value: string }>,
  parameters: [] as Array<{
    id: string;
    key: string;
    source: 'FIXED' | 'FIELD';
    value?: string;
    fieldId?: string;
  }>,
  continueMode: 'ON_SUCCESS',
  secret: '',
});
