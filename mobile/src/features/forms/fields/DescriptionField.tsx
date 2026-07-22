import type { MobileFieldProps } from '../schema/types';
import { fieldLabel, FieldShell } from './fieldShared';

export function DescriptionField(props: MobileFieldProps) {
  return (
    <FieldShell label={fieldLabel(props.node)}>
      <p style={{ margin: 0, color: 'rgba(0,0,0,0.68)' }}>
        {String(props.node.props?.text ?? '')}
      </p>
    </FieldShell>
  );
}
