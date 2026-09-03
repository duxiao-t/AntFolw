import { create } from 'zustand';
import { createClientId } from '../../shared/clientId';
import type { MobileFlowNode, MobileFormValues, MobileSchemaNode } from './schema/types';

export type SubmitFlowState = {
  formCode: string | null;
  draftId: number | null;
  reworkTaskId: number | null;
  values: MobileFormValues;
  selfSelected: Record<string, number[]>;
  selfSelectedUsers: Record<number, SelfSelectAssignee>;
  reset(): void;
};

export type SelfSelectRule = {
  nodeId: string;
  name: string;
  multiple: boolean;
  approvalMode: SelfSelectApprovalMode;
  assignees: SelfSelectAssignee[];
};

export type SelfSelectApprovalMode = 'OR' | 'AND' | 'RATIO' | 'SEQUENTIAL';

export type SelfSelectAssignee = {
  id: number;
  name: string;
  department?: string;
  employeeNo?: string | null;
  username?: string;
};

export const useSubmitFlowStore = create<SubmitFlowState>((set) => ({
  formCode: null,
  draftId: null,
  reworkTaskId: null,
  values: {},
  selfSelected: {},
  selfSelectedUsers: {},
  reset() {
    set({ formCode: null, draftId: null, reworkTaskId: null, values: {}, selfSelected: {}, selfSelectedUsers: {} });
  },
}));

export function beginSubmitFlow({
  formCode,
  draftId,
  values,
  reworkTaskId = null,
}: {
  formCode: string;
  draftId: number | null;
  values: MobileFormValues;
  reworkTaskId?: number | null;
}) {
  useSubmitFlowStore.setState({
    formCode,
    draftId,
    reworkTaskId,
    values,
    selfSelected: {},
    selfSelectedUsers: {},
  });
}

export function updateSelfSelected(nodeId: string, userIds: number[], user?: SelfSelectAssignee) {
  useSubmitFlowStore.setState((state) => ({
    selfSelected: {
      ...state.selfSelected,
      [nodeId]: userIds,
    },
    selfSelectedUsers: user
      ? { ...state.selfSelectedUsers, [user.id]: user }
      : state.selfSelectedUsers,
  }));
}

let idempotencyState: { payload: string; key: string } | null = null;

export function idempotencyKeyForPayload(payload: string) {
  if (!idempotencyState || idempotencyState.payload !== payload) {
    idempotencyState = {
      payload,
      key: createClientId('submit'),
    };
  }
  return idempotencyState.key;
}

export function clearIdempotencyKeyForPayload(payload: string) {
  if (idempotencyState?.payload === payload) {
    idempotencyState = null;
  }
}

export function findSelfSelectRules(schema: MobileSchemaNode[] | MobileFlowNode | null | undefined): SelfSelectRule[] {
  return nodesOf(schema).flatMap((node) => selfSelectRulesFromNode(node));
}

export function formSchemaWithoutSelfSelectRules(schema: MobileSchemaNode[]): MobileSchemaNode[] {
  return schema.flatMap((node) => {
    if (isSelfSelectNode(node)) {
      return [];
    }
    return [{
      ...node,
      children: node.children ? formSchemaWithoutSelfSelectRules(node.children) : undefined,
    }];
  });
}

function selfSelectRulesFromNode(node: MobileSchemaNode | MobileFlowNode): SelfSelectRule[] {
  const nested = [
    ...nodesOf(node.children),
    ...nodesOf((node as MobileFlowNode).branchs),
    ...nodesOf((node as MobileFlowNode).branches),
  ].flatMap((child) => selfSelectRulesFromNode(child));
  if (!isSelfSelectNode(node)) {
    return nested;
  }
  const selfSelect = objectProp(node.props?.selfSelect);
  return [
    {
      nodeId: node.id,
      name: nodeName(node),
      multiple: Boolean(selfSelect?.multiple ?? node.props?.multiple ?? node.props?.multiSelect),
      approvalMode: approvalMode(node.props?.mode),
      assignees: assigneesFromProps(node.props),
    },
    ...nested,
  ];
}

function nodeName(node: MobileSchemaNode | MobileFlowNode) {
  const values = [
    (node as MobileFlowNode).name,
    node.props?.name,
    node.props?.title,
    node.props?.nodeName,
    node.label,
  ];
  return String(values.find((value) => typeof value === 'string' && value.trim()) ?? node.id);
}

function approvalMode(value: unknown): SelfSelectApprovalMode {
  if (value === 'AND' || value === 'ALL') return 'AND';
  if (value === 'RATIO') return 'RATIO';
  if (value === 'SEQUENTIAL') return 'SEQUENTIAL';
  return 'OR';
}

function isSelfSelectNode(node: MobileSchemaNode | MobileFlowNode) {
  return node.props?.selfSelect === true || node.props?.assignedType === 'SELF_SELECT';
}

function assigneesFromProps(props: MobileSchemaNode['props']): SelfSelectAssignee[] {
  return arrayProp(props?.assignees ?? props?.candidates ?? props?.users).flatMap((item) => {
    if (typeof item !== 'object' || item == null) {
      return [];
    }
    const candidate = item as Record<string, unknown>;
    const id = candidate.id ?? candidate.userId ?? candidate.value;
    if (typeof id !== 'number' || !Number.isSafeInteger(id)) {
      return [];
    }
    return [{
      id,
      name: String(candidate.name ?? candidate.label ?? `用户#${id}`),
      department: typeof candidate.department === 'string' ? candidate.department : undefined,
    }];
  });
}

export function selectedAssigneeNames(
  rules: SelfSelectRule[],
  selfSelected: Record<string, number[]>,
  selectedUsers: Record<number, SelfSelectAssignee> = {},
) {
  return rules.flatMap((rule) => {
    const names = (selfSelected[rule.nodeId] ?? []).map((id) =>
      selectedUsers[id]?.name
        ?? rule.assignees.find((assignee) => assignee.id === id)?.name
        ?? `用户#${id}`);
    return names.length > 0 ? [{ nodeId: rule.nodeId, name: rule.name, names }] : [];
  });
}

function arrayProp(value: unknown) {
  return Array.isArray(value) ? value : [];
}

function nodesOf(value: MobileSchemaNode[] | MobileFlowNode | MobileFlowNode[] | null | undefined) {
  if (!value) {
    return [];
  }
  return Array.isArray(value) ? value : [value];
}

function objectProp(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value != null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}
