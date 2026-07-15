export type SchemaNode = {
  id: string;
  type: string;
  label?: string;
  props?: Record<string, any>;
  children?: SchemaNode[];
};

export type FieldMode = 'designer-preview' | 'runtime-fill' | 'readonly';

export type FieldComponentProps<TProps = any, TValue = any> = {
  node: SchemaNode;
  mode: FieldMode;
  value?: TValue;
  onChange?(value: TValue): void;
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
