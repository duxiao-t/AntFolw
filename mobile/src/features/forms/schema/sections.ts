import { isVisibleNode } from './validators';
import type { MobileFormValues, MobileSchemaNode } from './types';

export type FormSectionGroup = {
  id: string;
  title: string;
  description?: string;
  nodes: MobileSchemaNode[];
  fieldIds: string[];
};

export function buildFormSections(schema: MobileSchemaNode[], values: MobileFormValues): FormSectionGroup[] {
  const visibleNodes = visibleDescendantNodes(schema, values);
  const sections: FormSectionGroup[] = [];
  let looseNodes: MobileSchemaNode[] = [];

  function flushLoose() {
    if (looseNodes.length === 0) return;
    sections.push(toSection({
      id: sections.length === 0 ? 'default-section' : `default-section-${sections.length + 1}`,
      title: sections.length === 0 ? '表单内容' : '补充信息',
      nodes: looseNodes,
    }));
    looseNodes = [];
  }

  for (const node of visibleNodes) {
    if (node.type === 'section') {
      flushLoose();
      const section = toSection({
        id: node.id,
        title: sectionTitle(node),
        description: sectionDescription(node),
        nodes: node.children ?? [],
      });
      if (section.nodes.length > 0 || section.fieldIds.length > 0) {
        sections.push(section);
      }
      continue;
    }
    looseNodes.push(node);
  }
  flushLoose();

  return sections.length > 0 ? sections : [
    toSection({
      id: 'default-section',
      title: '表单内容',
      description: undefined,
      nodes: [],
    }),
  ];
}

export function fieldIdsInSection(section: FormSectionGroup): string[] {
  return section.fieldIds;
}

function toSection(input: {
  id: string;
  title: string;
  description?: string;
  nodes: MobileSchemaNode[];
}): FormSectionGroup {
  return {
    id: input.id,
    title: input.title,
    description: input.description,
    nodes: input.nodes,
    fieldIds: input.nodes.flatMap(collectFieldIds),
  };
}

function collectFieldIds(node: MobileSchemaNode): string[] {
  if (node.type === 'description') {
    return [];
  }
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

function sectionTitle(node: MobileSchemaNode) {
  const propTitle = node.props?.title ?? node.props?.label;
  if (typeof node.label === 'string' && node.label.trim()) return node.label.trim();
  if (typeof propTitle === 'string' && propTitle.trim()) return propTitle.trim();
  return '业务分区';
}

function sectionDescription(node: MobileSchemaNode) {
  const text = node.props?.description;
  if (typeof text === 'string' && text.trim()) return text.trim();
  return undefined;
}
