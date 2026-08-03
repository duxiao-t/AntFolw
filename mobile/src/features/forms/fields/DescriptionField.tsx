import type { MobileFieldProps } from '../schema/types';
import { fieldLabel, FieldShell } from './fieldShared';

export function DescriptionField(props: MobileFieldProps) {
  return (
    <FieldShell node={props.node} label={fieldLabel(props.node)}>
      <p className="af-field__description-text">
        {String(props.node.props?.text ?? '')}
      </p>
    </FieldShell>
  );
}
