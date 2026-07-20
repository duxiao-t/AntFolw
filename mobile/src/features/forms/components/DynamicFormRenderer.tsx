import type { CSSProperties } from 'react';
import { getFieldDefinition } from '../schema/fieldRegistry';
import type {
  FieldMode,
  FieldValidationErrors,
  MobileFormValues,
  MobileSchemaNode,
} from '../schema/types';

export type DynamicFormRendererProps = {
  schema: MobileSchemaNode[];
  values: MobileFormValues;
  mode: FieldMode;
  errors?: FieldValidationErrors;
  onValueChange: (fieldId: string, value: unknown) => void;
};

const rendererStyle: CSSProperties = {
  display: 'grid',
  gap: 2,
};

export function DynamicFormRenderer({
  schema,
  values,
  mode,
  errors = {},
  onValueChange,
}: DynamicFormRendererProps) {
  function renderNodes(nodes: MobileSchemaNode[]) {
    return nodes.map((node) => {
      const definition = getFieldDefinition(node.type);
      const FieldComponent = definition.Component;
      return (
        <FieldComponent
          key={node.id}
          node={node}
          value={values[node.id]}
          values={values}
          mode={mode}
          error={errors[node.id]}
          onValueChange={onValueChange}
          renderChildren={renderNodes}
        />
      );
    });
  }

  return <div style={rendererStyle}>{renderNodes(schema)}</div>;
}

export default DynamicFormRenderer;
