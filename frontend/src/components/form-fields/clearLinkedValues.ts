import type { SchemaNode } from '../../registry/types';

/** span_layout 只是布局容器，内部字段的数据键仍在顶层；可能有任意层嵌套，必须递归展开。 */
function flattenLayout(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.flatMap((node): SchemaNode[] =>
    node.type === 'span_layout'
      ? [node, ...flattenLayout(node.children ?? [])]
      : [node]);
}

export function clearLinkedValues(schema: SchemaNode[], sourceId: string, values: Record<string, any>) {
  // 早先只展开了一层 children，span_layout 套 span_layout 时内层联动字段不会被清掉——
  // 上游改了，下游还留着上一个上游值算出来的旧值。
  const fields = flattenLayout(schema);
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
