import { Input } from 'antd-mobile';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary, stringValue } from './fieldShared';

export function NumberField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  return (
    <FieldShell
      node={props.node}
      label={label}
      controlId={props.node.id}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(props.value) : undefined}
    >
      <Input
        id={props.node.id}
        className="af-control"
        inputMode="decimal"
        type="number"
        placeholder={String(props.node.props?.placeholder ?? '请输入')}
        value={stringValue(props.value)}
        onChange={(value) => props.onValueChange(props.node.id, value)}
      />
    </FieldShell>
  );
}
