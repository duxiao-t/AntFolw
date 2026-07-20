import { createElement, type ChangeEvent, type CSSProperties } from 'react';
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

function DescriptionField(props: MobileFieldProps) {
  return createElement(
    'section',
    { style: { ...fieldShellStyle, color: 'rgba(0,0,0,0.68)' } },
    props.node.label
      ? createElement('strong', { style: labelStyle }, props.node.label)
      : null,
    createElement('p', { style: { margin: 0 } }, String(props.node.props?.text ?? '')),
  );
}

function SpanLayoutField(props: MobileFieldProps) {
  return createElement(
    'section',
    {
      style: {
        display: 'grid',
        gap: 8,
        padding: '4px 0',
      },
    },
    props.renderChildren?.(props.node.children ?? []),
  );
}

function TableListField(props: MobileFieldProps) {
  return createElement(
    'section',
    {
      style: {
        display: 'grid',
        gap: 8,
        padding: '8px 0',
      },
    },
    props.node.label ? createElement('strong', { style: labelStyle }, props.node.label) : null,
    props.renderChildren?.(props.node.children ?? []),
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

function field(type: MobileFieldDefinition['type'],
               Component = GenericField): MobileFieldDefinition {
  return {
    type,
    Component,
    validate: validateRequired,
    summarize: (_node, value) => summarizeValue(value),
  };
}

export const registeredFields: MobileFieldDefinition[] = [
  field('text'),
  field('textarea'),
  field('number'),
  field('money'),
  field('date'),
  field('date_range'),
  field('select'),
  field('multi_select'),
  field('user_picker'),
  field('dept_picker'),
  field('file_upload'),
  field('description', DescriptionField),
  field('span_layout', SpanLayoutField),
  field('table_list', TableListField),
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
    validateNode(node, values, errors);
  }
  return errors;
}

function validateNode(node: MobileSchemaNode, values: MobileFormValues,
                      errors: FieldValidationErrors) {
  const definition = getFieldDefinition(node.type);
  const error = definition.validate(node, values[node.id]);
  if (error) {
    errors[node.id] = error;
  }
  for (const child of node.children ?? []) {
    validateNode(child, values, errors);
  }
}

function stringValue(value: unknown) {
  if (value == null) {
    return '';
  }
  return typeof value === 'string' ? value : String(value);
}
