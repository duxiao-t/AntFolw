import { CheckOutline, DownOutline } from 'antd-mobile-icons';
import { useEffect, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, fieldOptions, FieldShell, isRequired, optionLabel } from './fieldShared';
import { MobileSelectionPopup } from './MobileSelectionPopup';

export function SelectField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const value = selectedValue(props.value);
  const [selected, setSelected] = useState<string | number | null>(value);
  const [visible, setVisible] = useState(false);
  const options = fieldOptions(props.node);
  const selectedLabel = selected == null ? '' : optionLabel(props.node, selected);
  const placeholder = String(props.node.props?.placeholder ?? `选择${label}`);

  useEffect(() => {
    setSelected(value);
  }, [value]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{optionLabel(props.node, props.value)}</div> : undefined}
    >
      {options.length > 0 ? (
        <>
          <button
            type="button"
            className={`control form-picker${selectedLabel ? '' : ' af-field-picker--placeholder'}`}
            onClick={() => setVisible(true)}
          >
            <span className="picker-value">{selectedLabel || placeholder}</span>
            <DownOutline aria-hidden="true" />
          </button>
          <MobileSelectionPopup
            visible={visible}
            title={`选择${label}`}
            subtitle="请选择一项"
            onClose={() => setVisible(false)}
          >
            <div role="listbox" aria-label={label} className="af-full-picker__list">
              {options.map((option) => {
                const active = selected === option.value;
                return (
                  <button
                    key={option.value}
                    type="button"
                    role="option"
                    aria-label={option.label}
                    aria-selected={active}
                    className="af-full-picker__option af-full-picker__option--select"
                    disabled={option.disabled}
                    onClick={() => {
                      setSelected(option.value);
                      props.onValueChange(props.node.id, option.value);
                      setVisible(false);
                    }}
                  >
                    <span className="af-full-picker__avatar af-full-picker__avatar--choice" aria-hidden="true">
                      {option.label.trim().slice(0, 1)}
                    </span>
                    <span className="af-full-picker__option-text">
                      <strong>{option.label}</strong>
                    </span>
                    <span className="af-full-picker__option-status" aria-hidden="true">
                      {active ? <CheckOutline /> : null}
                    </span>
                  </button>
                );
              })}
            </div>
          </MobileSelectionPopup>
        </>
      ) : (
        <div className="af-field__empty-options">暂无可选项</div>
      )}
    </FieldShell>
  );
}

function selectedValue(value: unknown) {
  if (value === '') {
    return null;
  }
  return typeof value === 'string' || typeof value === 'number' ? value : null;
}
