import { create } from 'zustand';
import type { MobileFlowNode, MobileFormValues, MobileSchemaNode } from './schema/types';

export type SubmitFlowState = {
  formCode: string | null;
  draftId: number | null;
  values: MobileFormValues;
  selfSelected: Record<string, number[]>;
  reset(): void;
};

export type SelfSelectRule = {
  nodeId: string;
  name: string;
  multiple: boolean;
  assignees: SelfSelectAssignee[];
};

export type SelfSelectAssignee = {
  id: number;
  name: string;
};

export const useSubmitFlowStore = create<SubmitFlowState>((set) => ({
  formCode: null,
  draftId: null,
  values: {},
  selfSelected: {},
  reset() {
    set({ formCode: null, draftId: null, values: {}, selfSelected: {} });
  },
}));

export function beginSubmitFlow({
  formCode,
  draftId,
  values,
}: {
  formCode: string;
  draftId: number | null;
  values: MobileFormValues;
}) {
  useSubmitFlowStore.setState({
    formCode,
    draftId,
    values,
    selfSelected: {},
  });
}

export function updateSelfSelected(nodeId: string, userIds: number[]) {
  useSubmitFlowStore.setState((state) => ({
    selfSelected: {
      ...state.selfSelected,
      [nodeId]: userIds,
    },
  }));
}

let idempotencyState: { payload: string; key: string } | null = null;

export function idempotencyKeyForPayload(payload: string) {
  if (!idempotencyState || idempotencyState.payload !== payload) {
    idempotencyState = { payload, key: crypto.randomUUID() };
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
      name: String(node.props?.name ?? node.props?.title ?? node.props?.nodeName ?? node.label ?? node.id),
      multiple: Boolean(selfSelect?.multiple ?? node.props?.multiple ?? node.props?.multiSelect),
      assignees: assigneesFromProps(node.props),
    },
    ...nested,
  ];
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
    return [{ id, name: String(candidate.name ?? candidate.label ?? `用户#${id}`) }];
  });
}

export function selectedAssigneeNames(
  rules: SelfSelectRule[],
  selfSelected: Record<string, number[]>,
) {
  return rules.flatMap((rule) => {
    const names = (selfSelected[rule.nodeId] ?? []).map((id) =>
      rule.assignees.find((assignee) => assignee.id === id)?.name ?? `用户#${id}`);
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
