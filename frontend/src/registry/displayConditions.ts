import type { DisplayCondition, SchemaNode } from './types';

export function matchesDisplayCondition(
  condition: DisplayCondition | undefined,
  values: Record<string, any>,
) {
  if (!condition?.fieldId) return true;
  const sourceValue = values[condition.fieldId];
  const targetValue = condition.value;
  switch (condition.operator ?? 'eq') {
    case 'in':
      return Array.isArray(targetValue)
        && targetValue.some((item) => String(item) === String(sourceValue ?? ''));
    case 'ne':
    case '!=':
    case '!==':
      return String(sourceValue ?? '') !== String(targetValue ?? '');
    case 'contains':
      return Array.isArray(sourceValue)
        ? sourceValue.map(String).includes(String(targetValue ?? ''))
        : String(sourceValue ?? '').includes(String(targetValue ?? ''));
    case 'empty':
      return isEmptyValue(sourceValue);
    case 'notEmpty':
      return !isEmptyValue(sourceValue);
    case 'gt':
    case '>':
      return numberCompare(sourceValue, targetValue, (left, right) => left > right);
    case 'gte':
    case '>=':
      return numberCompare(sourceValue, targetValue, (left, right) => left >= right);
    case 'lt':
    case '<':
      return numberCompare(sourceValue, targetValue, (left, right) => left < right);
    case 'lte':
    case '<=':
      return numberCompare(sourceValue, targetValue, (left, right) => left <= right);
    default:
      return String(sourceValue ?? '') === String(targetValue ?? '');
  }
}

export function isVisibleNode(
  node: SchemaNode,
  values: Record<string, any>,
  visibleIds?: ReadonlySet<string>,
) {
  return node.props?.hidden !== true
    && (!node.props?.displayCondition?.fieldId
      || !visibleIds
      || visibleIds.has(node.props.displayCondition.fieldId))
    && matchesDisplayCondition(node.props?.displayCondition, values);
}

export function visibleNodeIds(nodes: SchemaNode[], values: Record<string, any>) {
  const allIds = new Set(flattenNodes(nodes).map((node) => node.id));
  const visibleIds = new Set<string>();
  const visit = (items: SchemaNode[], parentVisible: boolean) => {
    items.forEach((node) => {
      const sourceId = node.props?.displayCondition?.fieldId;
      const sourceVisible = !sourceId || !allIds.has(sourceId) || visibleIds.has(sourceId);
      const visible = parentVisible && sourceVisible && isVisibleNode(node, values);
      if (visible) visibleIds.add(node.id);
      visit(node.children ?? [], visible);
    });
  };
  visit(nodes, true);
  return visibleIds;
}

export function collectVisibleValues(nodes: SchemaNode[], values: Record<string, any>) {
  const output: Record<string, any> = {};
  const visibleIds = visibleNodeIds(nodes, values);
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) continue;
    if (node.type === 'table_list') {
      output[node.id] = Array.isArray(values[node.id])
        ? values[node.id].map((row: any) => collectVisibleValues(node.children ?? [], row ?? {}))
        : [];
    } else if (node.children?.length) {
      collectVisibleValuesInto(node.children, values, output, visibleIds);
    } else if (node.type !== 'description' && Object.hasOwn(values, node.id)) {
      output[node.id] = values[node.id];
    }
  }
  return output;
}

export function firstVisibleValidationError(nodes: SchemaNode[], values: Record<string, any>): string | null {
  return firstVisibleValidationErrorIn(nodes, values, visibleNodeIds(nodes, values));
}

function firstVisibleValidationErrorIn(
  nodes: SchemaNode[],
  values: Record<string, any>,
  visibleIds: ReadonlySet<string>,
): string | null {
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) continue;
    if (node.children?.length && node.type !== 'table_list') {
      const childError = firstVisibleValidationErrorIn(node.children, values, visibleIds);
      if (childError) return childError;
    }
    if (node.props?.required && isEmptyValue(values[node.id] ?? node.props.defaultValue)) {
      return String(node.props.validationMessage ?? `请填写${node.label ?? node.id}`);
    }
    const value = values[node.id] ?? node.props?.defaultValue;
    if ((node.type === 'number' || node.type === 'money') && !isEmptyValue(value)) {
      const number = Number(value);
      if (!Number.isFinite(number)) return `${node.label ?? node.id}必须是数字`;
      if (typeof node.props?.min === 'number' && number < node.props.min) {
        return `${node.label ?? node.id}不能小于${node.props.min}`;
      }
      if (typeof node.props?.max === 'number' && number > node.props.max) {
        return `${node.label ?? node.id}不能大于${node.props.max}`;
      }
    }
  }
  return null;
}

function collectVisibleValuesInto(
  nodes: SchemaNode[],
  values: Record<string, any>,
  output: Record<string, any>,
  visibleIds: ReadonlySet<string>,
) {
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) continue;
    if (node.type === 'table_list') {
      output[node.id] = Array.isArray(values[node.id])
        ? values[node.id].map((row: any) => collectVisibleValues(node.children ?? [], row ?? {}))
        : [];
    } else if (node.children?.length) {
      collectVisibleValuesInto(node.children, values, output, visibleIds);
    } else if (node.type !== 'description' && Object.hasOwn(values, node.id)) {
      output[node.id] = values[node.id];
    }
  }
}

function flattenNodes(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.flatMap((node) => [node, ...flattenNodes(node.children ?? [])]);
}

export function isEmptyValue(value: unknown) {
  return value == null
    || value === ''
    || (typeof value === 'string' && value.trim() === '')
    || (Array.isArray(value) && value.length === 0);
}

function numberCompare(
  sourceValue: unknown,
  targetValue: unknown,
  compare: (left: number, right: number) => boolean,
) {
  const left = Number(sourceValue);
  const right = Number(targetValue);
  return Number.isFinite(left) && Number.isFinite(right) && compare(left, right);
}
