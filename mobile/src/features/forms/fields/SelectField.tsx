import { Selector } from 'antd-mobile';
import { useEffect, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, fieldOptions, FieldShell, isRequired, optionLabel } from './fieldShared';

export function SelectField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const value = selectedValue(props.value);
  const [selected, setSelected] = useState<Array<string | number>>(value == null ? [] : [value]);
  useEffect(() => {
    setSelected(value == null ? [] : [value]);
  }, [value]);
  return (
    <FieldShell
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div>{optionLabel(props.node, props.value)}</div> : undefined}
    >
      <Selector
        options={fieldOptions(props.node)}
        value={selected}
        onChange={(next) => {
          setSelected(next);
          props.onValueChange(props.node.id, next[0] ?? '');
        }}
      />
    </FieldShell>
  );
}

function selectedValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? value : null;
}
