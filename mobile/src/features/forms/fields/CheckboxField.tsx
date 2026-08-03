import { useEffect, useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, fieldOptions, FieldShell, isRequired } from './fieldShared';

export function CheckboxField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const values = useMemo(() => arrayValue(props.value), [props.value]);
  const [selected, setSelected] = useState<Array<string | number>>(values);
  const options = fieldOptions(props.node);

  useEffect(() => {
    setSelected(values);
  }, [values]);

  const selectedLabels = options
    .filter((option) => selected.includes(option.value))
    .map((option) => option.label);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{selectedLabels.join('、') || '未填写'}</div> : undefined}
    >
      {options.length > 0 ? (
        <div className="af-choice-grid">
          {options.map((option) => {
            const isSelected = selected.includes(option.value);
            return (
              // biome-ignore lint/a11y/useSemanticElements: Plan requires mobile choice tiles to be semantic button controls with checkbox state.
              <button
                key={option.value}
                type="button"
                role="checkbox"
                aria-checked={isSelected}
                className={`af-choice-tile${isSelected ? ' af-choice-tile--selected' : ''}`}
                disabled={option.disabled}
                onClick={() => {
                  const next = isSelected
                    ? selected.filter((item) => item !== option.value)
                    : [...selected, option.value];
                  setSelected(next);
                  props.onValueChange(props.node.id, next);
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

function arrayValue(value: unknown): Array<string | number> {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is string | number =>
    typeof item === 'string' || typeof item === 'number');
}
