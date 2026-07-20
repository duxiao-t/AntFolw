import type { ComponentType, ReactNode } from 'react';

export type FieldTypeCode =
  | 'text'
  | 'textarea'
  | 'number'
  | 'money'
  | 'date'
  | 'date_range'
  | 'select'
  | 'multi_select'
  | 'user_picker'
  | 'dept_picker'
  | 'file_upload'
  | 'description'
  | 'span_layout'
  | 'table_list';

export type MobileSchemaNode = {
  id: string;
  type: FieldTypeCode | string;
  label?: string;
  props?: Record<string, unknown>;
  children?: MobileSchemaNode[];
};

export type MobileFormValues = Record<string, unknown>;

export type FieldMode = 'fill' | 'readonly';

export type FieldValidationErrors = Record<string, string>;

export type MobileFieldProps = {
  node: MobileSchemaNode;
  value: unknown;
  values: MobileFormValues;
  mode: FieldMode;
  error?: string;
  onValueChange: (fieldId: string, value: unknown) => void;
  renderChildren?: (children: MobileSchemaNode[]) => ReactNode;
};

export type MobileFieldDefinition = {
  type: FieldTypeCode | 'unsupported';
  Component: ComponentType<MobileFieldProps>;
  validate: (node: MobileSchemaNode, value: unknown) => string | null;
  summarize: (node: MobileSchemaNode, value: unknown) => string;
};
