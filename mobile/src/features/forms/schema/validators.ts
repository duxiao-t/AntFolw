import type { MobileSchemaNode } from './types';

export function isRequired(node: MobileSchemaNode) {
  return node.props?.required === true;
}

export function validateRequired(node: MobileSchemaNode, value: unknown) {
  if (!isRequired(node)) {
    return null;
  }
  if (value == null) {
    return requiredMessage(node);
  }
  if (typeof value === 'string' && value.trim() === '') {
    return requiredMessage(node);
  }
  if (Array.isArray(value) && value.length === 0) {
    return requiredMessage(node);
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
  return `请填写${node.label ?? node.id}`;
}
