import { createElement, type ChangeEvent, type CSSProperties } from 'react';
import { DateField } from '../fields/DateField';
import { DateRangeField } from '../fields/DateRangeField';
import { DescriptionField } from '../fields/DescriptionField';
import { DeptPickerField } from '../fields/DeptPickerField';
import { FileUploadField, hasBlockingUploadQueue } from '../fields/FileUploadField';
import { MoneyField } from '../fields/MoneyField';
import { MultiSelectField } from '../fields/MultiSelectField';
import { NumberField } from '../fields/NumberField';
import { SelectField } from '../fields/SelectField';
import { SpanLayoutField } from '../fields/SpanLayoutField';
import { TextField } from '../fields/TextField';
import { TextareaField } from '../fields/TextareaField';
import { TableListField } from '../fields/TableListField';
import { UserPickerField } from '../fields/UserPickerField';
import { summarizeValue, validateRequired } from './validators';
import type {
  FieldValidationErrors,
  MobileFieldDefinition,
  MobileFieldProps,
  MobileFormValues,
  MobileSchemaNode,
} from './types';

const fieldShellStyle: CSSProperties = {
  display: 'grid',
  gap: 6,
  padding: '12px 0',
};

const labelStyle: CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: 'var(--af-color-text)',
};

const controlStyle: CSSProperties = {
  width: '100%',
  minHeight: 44,
  border: '1px solid var(--af-color-border)',
  borderRadius: 'var(--af-radius-control)',
  background: 'var(--af-color-surface)',
  padding: '8px 10px',
};

const errorStyle: CSSProperties = {
  color: 'var(--af-color-danger)',
  fontSize: 12,
};

const readonlyStyle: CSSProperties = {
  minHeight: 32,
  color: 'rgba(0,0,0,0.72)',
};

function GenericField(props: MobileFieldProps) {
  const label = props.node.label ?? props.node.id;
  return createElement(
    'div',
    { style: fieldShellStyle },
    createElement('label', { htmlFor: props.node.id, style: labelStyle }, label),
    props.mode === 'readonly'
      ? createElement('div', { style: readonlyStyle }, summarizeValue(props.value))
      : createElement('input', {
          id: props.node.id,
          'aria-label': label,
          value: stringValue(props.value),
          onChange: (event: ChangeEvent<HTMLInputElement>) => {
            props.onValueChange(props.node.id, event.target.value);
          },
          style: controlStyle,
        }),
    props.error ? createElement('span', { style: errorStyle }, props.error) : null,
  );
}

function UnsupportedField(props: MobileFieldProps) {
  return createElement(
    'div',
    { role: 'group', style: fieldShellStyle },
    createElement('strong', { style: labelStyle }, props.node.label ?? props.node.id),
    createElement(
      'span',
      { style: { color: 'var(--af-color-danger)' } },
      `不支持的字段类型: ${props.node.type}`,
    ),
  );
}

function field(
  type: MobileFieldDefinition['type'],
  Component: MobileFieldDefinition['Component'] = GenericField,
  overrides: Partial<Pick<MobileFieldDefinition, 'validate' | 'summarize'>> = {},
): MobileFieldDefinition {
  return {
    type,
    Component,
    validate: overrides.validate ?? validateRequired,
    summarize: overrides.summarize ?? ((_node, value) => summarizeValue(value)),
  };
}

export const registeredFields: MobileFieldDefinition[] = [
  field('text', TextField),
  field('textarea', TextareaField),
  field('number', NumberField),
  field('money', MoneyField),
  field('date', DateField),
  field('date_range', DateRangeField, {
    validate: validateDateRange,
    summarize: (_node, value) => summarizeDateRange(value),
  }),
  field('select', SelectField, {
    summarize: (node, value) => optionSummary(node, value),
  }),
  field('multi_select', MultiSelectField, {
    summarize: (node, value) => multiOptionSummary(node, value),
  }),
  field('user_picker', UserPickerField, {
    validate: validateNumericValue,
    summarize: (_node, value) => summarizePickerValue('用户', value),
  }),
  field('dept_picker', DeptPickerField, {
    validate: validateNumericValue,
    summarize: (_node, value) => summarizePickerValue('部门', value),
  }),
  field('file_upload', FileUploadField, {
    validate: validateFileUpload,
    summarize: (_node, value) => summarizeUploadedFiles(value),
  }),
  field('description', DescriptionField, {
    validate: () => null,
    summarize: (node) => String(node.props?.text ?? ''),
  }),
  field('span_layout', SpanLayoutField, {
    validate: () => null,
    summarize: () => '布局',
  }),
  field('table_list', TableListField, {
    validate: validateTableList,
    summarize: (_node, value) => summarizeRows(value),
  }),
];

const unsupportedField: MobileFieldDefinition = {
  type: 'unsupported',
  Component: UnsupportedField,
  validate: () => null,
  summarize: () => '不支持的字段',
};

export function getFieldDefinition(type: string): MobileFieldDefinition {
  return registeredFields.find((fieldDefinition) => fieldDefinition.type === type)
    ?? unsupportedField;
}

export function validateSchemaValues(schema: MobileSchemaNode[],
                                     values: MobileFormValues): FieldValidationErrors {
  const errors: FieldValidationErrors = {};
  for (const node of schema) {
    validateNodeInValues(node, values, errors);
  }
  return errors;
}

function validateNodeInValues(node: MobileSchemaNode, values: MobileFormValues, errors: FieldValidationErrors) {
  if (node.type === 'table_list') {
    const error = validateTableList(node, values[node.id]);
    if (error) {
      errors[node.id] = error;
    }
    return;
  }
  const definition = getFieldDefinition(node.type);
  const error = definition.validate(node, values[node.id]);
  if (error) {
    errors[node.id] = error;
  }
  for (const child of node.children ?? []) {
    validateNodeInValues(child, values, errors);
  }
}

function stringValue(value: unknown) {
  if (value == null) {
    return '';
  }
  return typeof value === 'string' ? value : String(value);
}

function validateDateRange(node: MobileSchemaNode, value: unknown) {
  if (node.props?.required !== true) {
    return null;
  }
  if (!Array.isArray(value) || value.length < 2 || !value[0] || !value[1]) {
    return `请填写${node.label ?? node.id}`;
  }
  return null;
}

function summarizeDateRange(value: unknown) {
  if (!Array.isArray(value) || !value[0] || !value[1]) {
    return '未填写';
  }
  return `${String(value[0])} 至 ${String(value[1])}`;
}

function optionSummary(node: MobileSchemaNode, value: unknown) {
  const hit = options(node).find((option) => option.value === value);
  return hit?.label ?? summarizeValue(value);
}

function multiOptionSummary(node: MobileSchemaNode, value: unknown) {
  if (!Array.isArray(value) || value.length === 0) {
    return '未填写';
  }
  const selected = options(node)
    .filter((option) => value.includes(option.value))
    .map((option) => option.label);
  return selected.length > 0 ? selected.join('、') : summarizeValue(value);
}

function options(node: MobileSchemaNode) {
  const fieldOptions = node.props?.options;
  if (!Array.isArray(fieldOptions)) {
    return [];
  }
  return fieldOptions.flatMap((item) => {
    if (typeof item !== 'object' || item == null) {
      return [];
    }
    const option = item as Record<string, unknown>;
    const value = option.value;
    if (typeof value !== 'string' && typeof value !== 'number') {
      return [];
    }
    return [{ label: String(option.label ?? value), value }];
  });
}

function validateNumericValue(node: MobileSchemaNode, value: unknown) {
  if (value == null || value === '') {
    return node.props?.required === true ? `请填写${node.label ?? node.id}` : null;
  }
  return typeof value === 'number' ? null : `请填写${node.label ?? node.id}`;
}

function summarizePickerValue(prefix: string, value: unknown) {
  return typeof value === 'number' ? `${prefix}#${value}` : '未填写';
}

function validateFileUpload(node: MobileSchemaNode, value: unknown) {
  if (hasBlockingUploadQueue(value)) {
    return '仍有文件未完成上传';
  }
  if (node.props?.required !== true) {
    return null;
  }
  return Array.isArray(value) && value.length > 0 ? null : `请填写${node.label ?? node.id}`;
}

function summarizeUploadedFiles(value: unknown) {
  if (!Array.isArray(value) || value.length === 0) {
    return '未填写';
  }
  return `${value.length}个附件`;
}

function validateTableList(node: MobileSchemaNode, value: unknown) {
  const rows = Array.isArray(value) ? value : [];
  const minRows = numberValue(node.props?.minRows, node.props?.required === true ? 1 : 0);
  const maxRows = numberValue(node.props?.maxRows, Number.POSITIVE_INFINITY);
  if (rows.length < minRows) {
    return `请至少填写${minRows}行`;
  }
  if (rows.length > maxRows) {
    return `最多可填写${maxRows}行`;
  }
  const children = node.children ?? [];
  for (const [index, row] of rows.entries()) {
    if (typeof row !== 'object' || row == null) {
      return `第${index + 1}行: 请填写${node.label ?? node.id}`;
    }
    for (const child of children) {
      const rowErrors: FieldValidationErrors = {};
      validateNodeInValues(child, row as MobileFormValues, rowErrors);
      const error = Object.values(rowErrors)[0];
      if (error) {
        return `第${index + 1}行: ${error}`;
      }
    }
  }
  return null;
}

function summarizeRows(value: unknown) {
  return Array.isArray(value) ? `${value.length}行` : '未填写';
}

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}
