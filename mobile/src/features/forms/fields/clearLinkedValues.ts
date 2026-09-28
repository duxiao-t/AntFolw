import type { MobileFormValues, MobileSchemaNode } from '../schema/types';

export function clearLinkedValues(
  schema: MobileSchemaNode[],
  sourceId: string,
  values: MobileFormValues,
) {
  const fields = flatten(schema);
  const pending = [sourceId];
  const cleared = new Set<string>();
  const next = { ...values };
  while (pending.length) {
    const source = pending.shift();
    for (const field of fields) {
      const parent = (field.props?.optionSource as { dependency?: { fieldId?: string } })
        ?.dependency?.fieldId
        ?? (field.props?.dataLinkage as { fieldId?: string })?.fieldId;
      if (parent !== source || cleared.has(field.id)) continue;
      cleared.add(field.id);
      next[field.id] = field.type === 'multi_select' ? [] : undefined;
      pending.push(field.id);
    }
  }
  return next;
}

function flatten(nodes: MobileSchemaNode[]): MobileSchemaNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children ?? [])]);
}
