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

export function isVisibleNode(node: MobileSchemaNode, values: MobileFormValues) {
  return node.props?.hidden !== true && matchesDisplayCondition(node.props?.displayCondition, values);
}

export function visibleSchemaNodes(nodes: MobileSchemaNode[], values: MobileFormValues): MobileSchemaNode[] {
  return nodes.flatMap((node) => {
    if (!isVisibleNode(node, values)) {
      return [];
    }
    return [{
      ...node,
      children: node.children ? visibleSchemaNodes(node.children, values) : undefined,
    }];
  });
}

export function collectVisibleValues(nodes: MobileSchemaNode[], values: MobileFormValues): MobileFormValues {
  const next: MobileFormValues = {};
  collectVisibleValueNodes(nodes, values, next);
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
) {
  for (const node of nodes) {
    if (!isVisibleNode(node, values)) {
      continue;
    }
    if (node.type === 'table_list') {
      output[node.id] = collectVisibleTableRows(node, values[node.id]);
      continue;
    }
    if (node.children?.length) {
      collectVisibleValueNodes(node.children, values, output);
      continue;
    }
    if (Object.hasOwn(values, node.id)) {
      output[node.id] = values[node.id];
    }
  }
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
