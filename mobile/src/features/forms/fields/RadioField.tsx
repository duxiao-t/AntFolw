import { Selector } from 'antd-mobile';
import { useEffect, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, fieldOptions, FieldShell, isRequired, optionLabel } from './fieldShared';

export function RadioField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const value = selectedValue(props.value);
  const [selected, setSelected] = useState<Array<string | number>>(value == null ? [] : [value]);

  useEffect(() => {
    setSelected(value == null ? [] : [value]);
  }, [value]);

  const options = fieldOptions(props.node);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{optionLabel(props.node, props.value)}</div> : undefined}
    >
      {options.length > 0 ? (
        <Selector
          options={options}
          value={selected}
          onChange={(next) => {
            setSelected(next);
            props.onValueChange(props.node.id, next[0] ?? '');
          }}
        />
      ) : (
        <div className="af-field__empty-options">暂无可选项</div>
      )}
    </FieldShell>
  );
}

function selectedValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? value : null;
}
