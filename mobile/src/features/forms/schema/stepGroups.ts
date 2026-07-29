import { isVisibleNode } from './validators';
import type { MobileFormValues, MobileSchemaNode } from './types';

const AUTO_GROUP_SIZE = 6;

export type FormStepGroup = {
  id: string;
  title: string;
  description?: string;
  nodes: MobileSchemaNode[];
  fieldIds: string[];
};

export function buildFormStepGroups(schema: MobileSchemaNode[], values: MobileFormValues): FormStepGroup[] {
  const groups: FormStepGroup[] = [];
  let pendingDescription: string | undefined;
  let looseNodes: MobileSchemaNode[] = [];

  function flushLoose(title = groups.length === 0
    ? pendingDescription
      ? looseNodes[0]?.label ?? '基础信息'
      : '基础信息'
    : '补充信息') {
    if (looseNodes.length === 0) return;
    for (let index = 0; index < looseNodes.length; index += AUTO_GROUP_SIZE) {
      const chunk = looseNodes.slice(index, index + AUTO_GROUP_SIZE);
      const sequence = groups.length + 1;
      groups.push(toGroup({
        id: `auto-${sequence}`,
        title: index === 0 ? title : `${title}${Math.floor(index / AUTO_GROUP_SIZE) + 1}`,
        description: index === 0 ? pendingDescription : undefined,
        nodes: chunk,
      }));
    }
    looseNodes = [];
    pendingDescription = undefined;
  }

  for (const node of schema) {
    if (!isVisibleNode(node, values)) continue;
    if (node.type === 'description') {
      const text = descriptionText(node);
      if (text) pendingDescription = text;
      continue;
    }
    if (node.type === 'span_layout') {
      flushLoose();
      const group = toGroup({
        id: node.id,
        title: node.label ?? '表单分组',
        description: pendingDescription,
        nodes: visibleDescendantNodes(node.children ?? [], values),
      });
      if (group.fieldIds.length > 0) groups.push(group);
      pendingDescription = undefined;
      continue;
    }
    if (node.type === 'table_list') {
      flushLoose();
      groups.push(toGroup({
        id: node.id,
        title: node.label ?? '明细',
        description: pendingDescription,
        nodes: [node],
      }));
      pendingDescription = undefined;
      continue;
    }
    looseNodes.push(node);
  }
  flushLoose();

  return groups.length > 0 ? groups : [toGroup({ id: 'empty', title: '表单内容', nodes: [] })];
}

export function fieldIdsInStep(group: FormStepGroup): string[] {
  return group.fieldIds;
}

function toGroup(input: {
  id: string;
  title: string;
  description?: string;
  nodes: MobileSchemaNode[];
}): FormStepGroup {
  return {
    id: input.id,
    title: input.title,
    description: input.description,
    nodes: input.nodes,
    fieldIds: input.nodes.flatMap(collectFieldIds),
  };
}

function collectFieldIds(node: MobileSchemaNode): string[] {
  if (node.type === 'description') return [];
  if (node.children && node.type !== 'table_list') {
    return node.children.flatMap(collectFieldIds);
  }
  return [node.id];
}

function visibleDescendantNodes(nodes: MobileSchemaNode[], values: MobileFormValues): MobileSchemaNode[] {
  return nodes.flatMap((node) => {
    if (!isVisibleNode(node, values)) return [];
    return [{
      ...node,
      children: node.children ? visibleDescendantNodes(node.children, values) : undefined,
    }];
  });
}

function descriptionText(node: MobileSchemaNode): string {
  const text = node.props?.text;
  if (typeof text === 'string' && text.trim()) return text.trim();
  return node.label ?? '';
}
