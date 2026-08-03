import { getFieldDefinition } from '../schema/fieldRegistry';
import { isVisibleNode } from '../schema/validators';
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

export function DynamicFormRenderer({
  schema,
  values,
  mode,
  errors = {},
  onValueChange,
}: DynamicFormRendererProps) {
  function renderNodes(nodes: MobileSchemaNode[]) {
    return nodes.flatMap((node) => {
      if (!isVisibleNode(node, values)) {
        return [];
      }
      const definition = getFieldDefinition(node.type);
      const FieldComponent = definition.Component;
      return [
        <div key={node.id} className="af-form-renderer__item" data-field-id={node.id}>
          <FieldComponent
            node={node}
            value={values[node.id]}
            values={values}
            mode={mode}
            error={errors[node.id]}
            onValueChange={onValueChange}
            renderChildren={renderNodes}
          />
        </div>,
      ];
    });
  }

  return <div className="af-form-renderer">{renderNodes(schema)}</div>;
}

export default DynamicFormRenderer;
