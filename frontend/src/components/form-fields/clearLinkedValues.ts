import type { SchemaNode } from '../../registry/types';

export function clearLinkedValues(schema: SchemaNode[], sourceId: string, values: Record<string, any>) {
  const fields = schema.flatMap((node): SchemaNode[] => node.type === 'span_layout'
    ? [node, ...(node.children ?? [])] : [node]);
  const pending = [sourceId];
  const cleared = new Set<string>();
  const next = { ...values };
  while (pending.length) {
    const source = pending.shift();
    for (const field of fields) {
      const parent = field.props?.optionSource?.dependency?.fieldId ?? field.props?.dataLinkage?.fieldId;
      if (parent !== source || cleared.has(field.id)) continue;
      cleared.add(field.id);
      next[field.id] = field.type === 'multi_select' ? [] : undefined;
      pending.push(field.id);
    }
  }
  return next;
}
