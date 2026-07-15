import { formRegistry } from '../../registry/formRegistry';
import type { FieldMode, SchemaNode } from '../../registry/types';

type Props = {
  schema: SchemaNode[];
  mode: FieldMode;
  value?: any;
  onChange?(v: any): void;
};

export function FormRenderer({ schema, mode, value, onChange }: Props) {
  return (
    <div data-canvas={mode === 'designer-preview' ? 'true' : undefined}>
      {schema.map((node) => {
        const ft = formRegistry[node.type];
        if (!ft) return null;
        const nodeValue = value?.[node.id];
        return (
          <div key={node.id} style={{ margin: '8px 0' }}>
            <ft.Component
              node={node}
              mode={mode}
              value={nodeValue}
              onChange={(v: any) =>
                onChange?.({ ...(value ?? {}), [node.id]: v })
              }
            />
          </div>
        );
      })}
    </div>
  );
}
