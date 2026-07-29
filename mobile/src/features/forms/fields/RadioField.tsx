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
        <div className="af-choice-grid" role="radiogroup" aria-label={label}>
          {options.map((option) => {
            const isSelected = selected[0] === option.value;
            return (
              // biome-ignore lint/a11y/useSemanticElements: Plan requires mobile choice tiles to be semantic button controls with radio state.
              <button
                key={option.value}
                type="button"
                role="radio"
                aria-checked={isSelected}
                className="af-choice-tile"
                disabled={option.disabled}
                onClick={() => {
                  setSelected([option.value]);
                  props.onValueChange(props.node.id, option.value);
                }}
              >
                {option.label}
              </button>
            );
          })}
        </div>
      ) : (
        <div className="af-field__empty-options">暂无可选项</div>
      )}
    </FieldShell>
  );
}

function selectedValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? value : null;
}
