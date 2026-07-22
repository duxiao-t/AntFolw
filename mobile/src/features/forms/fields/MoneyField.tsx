import { Input } from 'antd-mobile';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, FieldShell, isRequired, readonlySummary, stringValue } from './fieldShared';

export function MoneyField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  return (
    <FieldShell
      label={label}
      controlId={props.node.id}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? readonlySummary(props.value) : undefined}
    >
      <Input
        id={props.node.id}
        inputMode="decimal"
        placeholder="0.00"
        value={stringValue(props.value)}
        onChange={(value) => props.onValueChange(props.node.id, value)}
      />
    </FieldShell>
  );
}
