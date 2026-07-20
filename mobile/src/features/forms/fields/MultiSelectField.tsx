import { Selector } from 'antd-mobile';
import { useEffect, useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, fieldOptions, FieldShell, isRequired } from './fieldShared';

export function MultiSelectField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const values = useMemo(() => arrayValue(props.value), [props.value]);
  const [selected, setSelected] = useState<Array<string | number>>(values);
  useEffect(() => {
    setSelected(values);
  }, [values]);
  const selectedLabels = fieldOptions(props.node)
    .filter((option) => selected.includes(option.value))
    .map((option) => option.label);
  return (
    <FieldShell
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div>{selectedLabels.join('、') || '未填写'}</div> : undefined}
    >
      <Selector
        multiple
        options={fieldOptions(props.node)}
        value={selected}
        onChange={(next) => {
          setSelected(next);
          props.onValueChange(props.node.id, next);
        }}
      />
    </FieldShell>
  );
}

function arrayValue(value: unknown): Array<string | number> {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is string | number =>
    typeof item === 'string' || typeof item === 'number');
}
