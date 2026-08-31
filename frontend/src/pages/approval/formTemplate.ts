import { formRegistry } from '../../registry/formRegistry';
import type { SchemaNode } from '../../registry/types';

export const FORM_TEMPLATE_FORMAT = 'antflow-form-template';
export const FORM_TEMPLATE_VERSION = 1;
export const MAX_FORM_TEMPLATE_BYTES = 5 * 1024 * 1024;

export type FormTemplate = {
  format: typeof FORM_TEMPLATE_FORMAT;
  version: typeof FORM_TEMPLATE_VERSION;
  name: string;
  description?: string;
  schema: SchemaNode[];
};

export function buildFormTemplate(definition: {
  name: string;
  description?: string;
  schema: SchemaNode[] | string;
}): FormTemplate {
  const schema =
    typeof definition.schema === 'string'
      ? JSON.parse(definition.schema)
      : definition.schema;
  validateSchema(schema);
  return {
    format: FORM_TEMPLATE_FORMAT,
    version: FORM_TEMPLATE_VERSION,
    name: definition.name,
    ...(definition.description ? { description: definition.description } : {}),
    schema,
  };
}

export function parseFormTemplate(text: string): FormTemplate {
  if (new TextEncoder().encode(text).byteLength > MAX_FORM_TEMPLATE_BYTES) {
    throw new Error('模板文件不能超过 5 MB');
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error('模板不是有效的 JSON 文件');
  }
  if (
    !isRecord(value) ||
    value.format !== FORM_TEMPLATE_FORMAT ||
    value.version !== FORM_TEMPLATE_VERSION
  ) {
    throw new Error('不支持的表单模板格式或版本');
  }
  if (typeof value.name !== 'string' || !value.name.trim()) {
    throw new Error('模板缺少表单名称');
  }
  if (value.description != null && typeof value.description !== 'string') {
    throw new Error('模板说明格式不正确');
  }
  validateSchema(value.schema);
  return value as FormTemplate;
}

function validateSchema(value: unknown): asserts value is SchemaNode[] {
  if (!Array.isArray(value)) throw new Error('模板缺少有效的表单结构');
  const ids = new Set<string>();
  const visit = (nodes: unknown[]) => {
    for (const item of nodes) {
      if (
        !isRecord(item) ||
        typeof item.id !== 'string' ||
        !item.id ||
        typeof item.type !== 'string' ||
        !formRegistry[item.type]
      ) {
        throw new Error('模板包含无法识别的表单字段');
      }
      if (!ids.add(item.id)) throw new Error(`模板包含重复字段：${item.id}`);
      if (item.children != null) {
        if (!Array.isArray(item.children))
          throw new Error('模板字段的子结构格式不正确');
        visit(item.children);
      }
    }
  };
  visit(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
