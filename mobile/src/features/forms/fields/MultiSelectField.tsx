import { DownOutline } from 'antd-mobile-icons';
import { useEffect, useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldError, fieldLabel, fieldOptions, FieldShell, isRequired } from './fieldShared';
import { MobileSelectionPopup } from './MobileSelectionPopup';

export function MultiSelectField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const values = useMemo(() => arrayValue(props.value), [props.value]);
  const [selected, setSelected] = useState<Array<string | number>>(values);
  const [draftSelected, setDraftSelected] = useState<Array<string | number>>(values);
  const [visible, setVisible] = useState(false);
  const options = fieldOptions(props.node);

  useEffect(() => {
    setSelected(values);
    if (!visible) {
      setDraftSelected(values);
    }
  }, [values, visible]);

  const selectedLabels = options
    .filter((option) => selected.includes(option.value))
    .map((option) => option.label);
  const placeholder = String(props.node.props?.placeholder ?? `选择${label}`);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{selectedLabels.join('、') || '未填写'}</div> : undefined}
    >
      {options.length > 0 ? (
        <>
          <button
            type="button"
            className={`control form-picker control--multi${selectedLabels.length > 0 ? '' : ' af-field-picker--placeholder'}`}
            onClick={() => {
              setDraftSelected(selected);
              setVisible(true);
            }}
          >
            {selectedLabels.length > 0 ? (
              <span className="selected-tags">
                {selectedLabels.map((item) => <span key={item}>{item}</span>)}
              </span>
            ) : (
              <span className="picker-value">{placeholder}</span>
            )}
            <DownOutline aria-hidden="true" />
          </button>
          <MobileSelectionPopup
            visible={visible}
            title={`选择${label}`}
            subtitle={`已选 ${draftSelected.length} 项`}
            onClose={closePicker}
            footer={(
              <>
                <button type="button" className="btn btn--ghost btn--lg" onClick={closePicker}>
                  取消
                </button>
                <button type="button" className="btn btn--success btn--lg" onClick={confirmPicker}>
                  完成
                </button>
              </>
            )}
          >
            <fieldset className="af-full-picker__list af-full-picker__fieldset">
              <legend className="visually-hidden">{label}</legend>
              {options.map((option) => {
                const checked = draftSelected.includes(option.value);
                return (
                  <label
                    key={option.value}
                    data-checked={checked ? 'true' : 'false'}
                    data-disabled={option.disabled ? 'true' : 'false'}
                    className="af-full-picker__option af-full-picker__option--select af-full-picker__option--check"
                  >
                    <span className="af-full-picker__avatar af-full-picker__avatar--choice" aria-hidden="true">
                      {option.label.trim().slice(0, 1)}
                    </span>
                    <span className="af-full-picker__option-text">
                      <strong>{option.label}</strong>
                    </span>
                    <input
                      type="checkbox"
                      aria-label={option.label}
                      checked={checked}
                      disabled={option.disabled}
                      className="af-full-picker__native-check"
                      onChange={() => toggleDraft(option.value)}
                    />
                  </label>
                );
              })}
            </fieldset>
          </MobileSelectionPopup>
        </>
      ) : (
        <div className="af-field__empty-options">暂无可选项</div>
      )}
    </FieldShell>
  );

  function closePicker() {
    setDraftSelected(selected);
    setVisible(false);
  }

  function confirmPicker() {
    const next = [...draftSelected];
    setSelected(next);
    props.onValueChange(props.node.id, next);
    setVisible(false);
  }

  function toggleDraft(value: string | number) {
    setDraftSelected((current) =>
      current.includes(value)
        ? current.filter((item) => item !== value)
        : [...current, value],
    );
  }
}

function arrayValue(value: unknown): Array<string | number> {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is string | number =>
    typeof item === 'string' || typeof item === 'number');
}
