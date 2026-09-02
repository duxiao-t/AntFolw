import { getFieldDefinition } from '../schema/fieldRegistry';
import { visibleNodeIds } from '../schema/validators';
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
  modeOverride?: Record<string, FieldMode>;
  errors?: FieldValidationErrors;
  onValueChange: (fieldId: string, value: unknown) => void;
};

export function DynamicFormRenderer({
  schema,
  values,
  mode,
  modeOverride = {},
  errors = {},
  onValueChange,
}: DynamicFormRendererProps) {
  const visibleIds = visibleNodeIds(schema, values);
  function renderNodes(nodes: MobileSchemaNode[]) {
    return nodes.flatMap((node) => {
      const effectiveMode = modeOverride[node.id] ?? mode;
      if (effectiveMode === 'hidden') {
        return [];
      }
      if (!visibleIds.has(node.id)) {
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
            mode={effectiveMode}
            modeOverride={modeOverride}
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
