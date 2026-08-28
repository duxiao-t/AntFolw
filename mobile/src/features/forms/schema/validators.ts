import type { MobileFormValues, MobileSchemaNode } from './types';

export function isRequired(node: MobileSchemaNode) {
  return node.props?.required === true;
}

export function validateRequired(node: MobileSchemaNode, value: unknown) {
  if (!isRequired(node)) {
    return null;
  }
  if (isEmptyValue(value)) {
    return requiredMessage(node);
  }
  return null;
}

export function validateCommonRules(node: MobileSchemaNode, value: unknown) {
  const requiredError = validateRequired(node, value);
  if (requiredError) {
    return requiredError;
  }
  if (isEmptyValue(value)) {
    return null;
  }

  const label = fieldLabel(node);
  const minLength = numericRule(node, 'minLength');
  const maxLength = numericRule(node, 'maxLength');
  const minChecked = numericRule(node, 'minChecked');
  const maxChecked = numericRule(node, 'maxChecked') ?? numericRule(node, 'maxSelected');
  const maxSelected = numericRule(node, 'maxSelected');

  if (node.type === 'number' || node.type === 'money') {
    const number = Number(value);
    const min = numericRule(node, 'min');
    const max = numericRule(node, 'max');
    if (!Number.isFinite(number)) return `${label}必须是数字`;
    if (min != null && number < min) return `${label}不能小于${min}`;
    if (max != null && number > max) return `${label}不能大于${max}`;
  }

  if (minLength != null && String(value).length < minLength) {
    return `${label}不能少于${minLength}个字符`;
  }
  if (maxLength != null && String(value).length > maxLength) {
    return `${label}不能超过${maxLength}个字符`;
  }
  if (node.props?.pattern) {
    try {
      if (!new RegExp(String(node.props.pattern)).test(String(value))) {
        return String(node.props.validationMessage ?? `${label}格式不正确`);
      }
    } catch {
      return `${label}校验规则无效`;
    }
  }
  if (Array.isArray(value)) {
    if (minChecked != null && value.length < minChecked) {
      return `${label}至少选择${minChecked}项`;
    }
    if (maxChecked != null && value.length > maxChecked) {
      return `${label}最多选择${maxChecked}项`;
    }
    if (maxSelected != null && value.length > maxSelected) {
      return `${label}最多选择${maxSelected}项`;
    }
  }
  return null;
}

export function summarizeValue(value: unknown) {
  if (value == null || value === '') {
    return '未填写';
  }
  if (Array.isArray(value)) {
    return value.length === 0 ? '未填写' : value.join('、');
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function requiredMessage(node: MobileSchemaNode) {
  return `请填写${fieldLabel(node)}`;
}

export function fieldLabel(node: MobileSchemaNode) {
  return node.label ?? String(node.props?.title ?? node.props?.label ?? node.id);
}

export function fieldDescription(node: MobileSchemaNode) {
  const text = node.props?.questionDescription ?? node.props?.description;
  return typeof text === 'string' && text.trim() ? text : null;
}

export function fieldHelp(node: MobileSchemaNode) {
  const text = node.props?.helpText ?? node.props?.help;
  return typeof text === 'string' && text.trim() ? text : null;
}

export function isVisibleNode(
  node: MobileSchemaNode,
  values: MobileFormValues,
  visibleIds?: ReadonlySet<string>,
) {
  const sourceId = displayConditionSourceId(node);
  return node.props?.hidden !== true
    && (!sourceId || !visibleIds || visibleIds.has(sourceId))
    && matchesDisplayCondition(node.props?.displayCondition, values);
}

export function visibleNodeIds(nodes: MobileSchemaNode[], values: MobileFormValues) {
  const allIds = new Set(flattenNodes(nodes).map((node) => node.id));
  const visibleIds = new Set<string>();
  const visit = (items: MobileSchemaNode[], parentVisible: boolean) => {
    items.forEach((node) => {
      const sourceId = displayConditionSourceId(node);
      const sourceVisible = !sourceId || !allIds.has(sourceId) || visibleIds.has(sourceId);
      const visible = parentVisible && sourceVisible && isVisibleNode(node, values);
      if (visible) visibleIds.add(node.id);
      visit(node.children ?? [], visible);
    });
  };
  visit(nodes, true);
  return visibleIds;
}

export function visibleSchemaNodes(nodes: MobileSchemaNode[], values: MobileFormValues): MobileSchemaNode[] {
  const visibleIds = visibleNodeIds(nodes, values);
  return visibleSchemaNodesFromIds(nodes, visibleIds);
}

function visibleSchemaNodesFromIds(
  nodes: MobileSchemaNode[],
  visibleIds: ReadonlySet<string>,
): MobileSchemaNode[] {
  return nodes.flatMap((node) => {
    if (!visibleIds.has(node.id)) {
      return [];
    }
    return [{
      ...node,
      children: node.children ? visibleSchemaNodesFromIds(node.children, visibleIds) : undefined,
    }];
  });
}

export function collectVisibleValues(nodes: MobileSchemaNode[], values: MobileFormValues): MobileFormValues {
  const next: MobileFormValues = {};
  collectVisibleValueNodes(nodes, values, next, visibleNodeIds(nodes, values));
  return next;
}

export function isEmptyValue(value: unknown) {
  return (
    value == null ||
    value === '' ||
    (typeof value === 'string' && value.trim() === '') ||
    (Array.isArray(value) && value.length === 0)
  );
}

function collectVisibleValueNodes(
  nodes: MobileSchemaNode[],
  values: MobileFormValues,
  output: MobileFormValues,
  visibleIds: ReadonlySet<string>,
) {
  for (const node of nodes) {
    if (!visibleIds.has(node.id)) {
      continue;
    }
    if (node.type === 'table_list') {
      output[node.id] = collectVisibleTableRows(node, values[node.id]);
      continue;
    }
    if (node.children?.length) {
      collectVisibleValueNodes(node.children, values, output, visibleIds);
      continue;
    }
    if (Object.hasOwn(values, node.id)) {
      output[node.id] = values[node.id];
    }
  }
}

function displayConditionSourceId(node: MobileSchemaNode) {
  const condition = node.props?.displayCondition;
  if (typeof condition !== 'object' || condition == null || Array.isArray(condition)) return null;
  const sourceId = (condition as Record<string, unknown>).fieldId
    ?? (condition as Record<string, unknown>).field;
  return typeof sourceId === 'string' && sourceId ? sourceId : null;
}

function flattenNodes(nodes: MobileSchemaNode[]): MobileSchemaNode[] {
  return nodes.flatMap((node) => [node, ...flattenNodes(node.children ?? [])]);
}

function collectVisibleTableRows(node: MobileSchemaNode, value: unknown) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((row) => {
    if (typeof row !== 'object' || row == null) {
      return {};
    }
    return collectVisibleValues(node.children ?? [], row as MobileFormValues);
  });
}

function matchesDisplayCondition(condition: unknown, values: MobileFormValues) {
  if (typeof condition !== 'object' || condition == null || Array.isArray(condition)) {
    return true;
  }
  const rule = condition as Record<string, unknown>;
  const fieldId = rule.fieldId ?? rule.field;
  if (typeof fieldId !== 'string' || !fieldId) {
    return true;
  }
  const sourceValue = values[fieldId];
  const targetValue = rule.value;
  switch (String(rule.operator ?? 'eq')) {
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
    case 'eq':
    case '==':
    case '===':
      return String(sourceValue ?? '') === String(targetValue ?? '');
    default:
      return String(sourceValue ?? '') === String(targetValue ?? '');
  }
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

function numericRule(node: MobileSchemaNode, key: string) {
  const value = node.props?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
