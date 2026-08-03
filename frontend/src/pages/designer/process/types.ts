export type NodeType =
  | 'ROOT'
  | 'APPROVAL'
  | 'CC'
  | 'CONDITIONS'
  | 'CONDITION'
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
  groupsType: 'OR',
  groups: [{ groupType: 'AND', conditions: [] as any[] }],
});