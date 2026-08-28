export type SchemaNodeProps = Record<string, any> & {
  dividerColor?: string;
};

export type SchemaNode = {
  id: string;
  type: string;
  label?: string;
  props?: SchemaNodeProps;
  children?: SchemaNode[];
};

export type FieldMode = 'designer-preview' | 'runtime-fill' | 'readonly' | 'hidden';

export type FieldComponentProps<_TProps = any, TValue = any> = {
  node: SchemaNode;
  mode: FieldMode;
  value?: TValue;
  onChange?(value: TValue): void;
  fieldModes?: Record<string, FieldMode>;
};

export type FieldType<TProps = any, TValue = any> = {
  type: string;
  label: string;
  icon: string;
  defaultProps: TProps;
  Component: React.FC<FieldComponentProps<TProps, TValue>>;
  ConfigPanel: React.FC<{ node: SchemaNode; onChange: (n: SchemaNode) => void }>;
  validate?(value: TValue, props: TProps): string | null;
};
