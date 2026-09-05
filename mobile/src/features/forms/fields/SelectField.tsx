import { CheckOutline, DownOutline } from 'antd-mobile-icons';
import { Input } from 'antd-mobile';
import { useEffect, useMemo, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import {
  allFieldOptions,
  fieldError,
  fieldLabel,
  fieldOptions,
  FieldShell,
  InlineFieldOptions,
  isRequired,
  optionLabel,
  selectDisplayStyle,
} from './fieldShared';
import { MobileSelectionPopup } from './MobileSelectionPopup';

const OTHER_OPTION_VALUE = '__antflow_other__';

export function SelectField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const value = selectedValue(props.value);
  const [selected, setSelected] = useState<string | number | null>(value);
  const [visible, setVisible] = useState(false);
  const [keyword, setKeyword] = useState('');
  const options = fieldOptions(props.node);
  const searchable = props.node.props?.showSearch === true;
  const clearable = props.node.props?.allowClear !== false;
  const displayStyle = selectDisplayStyle(props.node);
  const allOptions = allFieldOptions(props.node);
  const useColor = props.node.props?.enableOptionColor === true;
  const otherOption = options.find((option) => option.isOther);
  const standardValues = allOptions.filter((option) => !option.isOther).map((option) => option.value);
  const inferredOther = Boolean(
    otherOption && selected != null && !standardValues.includes(selected) && selected !== OTHER_OPTION_VALUE,
  );
  const [otherSelected, setOtherSelected] = useState(inferredOther);
  useEffect(() => {
    if (!otherOption) setOtherSelected(false);
    else if (selected != null) setOtherSelected(inferredOther);
  }, [inferredOther, otherOption, selected]);
  const selectedLabel = otherSelected ? '其他' : selected == null ? '' : optionLabel(props.node, selected);
  const placeholder = String(props.node.props?.placeholder ?? `选择${label}`);
  const visibleOptions = useMemo(() => {
    const query = keyword.trim().toLocaleLowerCase();
    return query
      ? options.filter((option) => option.label.toLocaleLowerCase().includes(query))
      : options;
  }, [keyword, options]);

  useEffect(() => {
    setSelected(value);
  }, [value]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{optionLabel(props.node, props.value) || '未填写'}</div> : undefined}
    >
      {props.mode !== 'readonly' && options.length > 0 ? (
        displayStyle !== 'dropdown' ? (
          <InlineFieldOptions
            label={label}
            displayStyle={displayStyle}
            options={options.map((option) => option.isOther
              ? { ...option, value: OTHER_OPTION_VALUE }
              : option)}
            selectedValues={otherSelected
              ? [OTHER_OPTION_VALUE]
              : selected == null ? [] : [selected]}
            multiple={false}
            useColor={useColor}
            onToggle={(option, active) => {
              if (active) {
                if (!clearable) return;
                clearSelection();
                return;
              }
              if (option.isOther) {
                setOtherSelected(true);
                setSelected(null);
                props.onValueChange(props.node.id, undefined);
              } else {
                setOtherSelected(false);
                setSelected(option.value);
                props.onValueChange(props.node.id, option.value);
              }
            }}
          />
        ) : (
        <>
          <button
            type="button"
            className={`control form-picker${selectedLabel ? '' : ' af-field-picker--placeholder'}`}
            onClick={() => {
              setKeyword('');
              setVisible(true);
            }}
          >
            <span className="picker-value">{selectedLabel || placeholder}</span>
            <DownOutline aria-hidden="true" />
          </button>
          <MobileSelectionPopup
            visible={visible}
            title={`选择${label}`}
            subtitle="请选择一项"
            presentation="sheet"
            headerAction={clearable && selectedLabel ? (
              <button type="button" className="af-full-picker__clear" onClick={clearSelection}>
                清空
              </button>
            ) : undefined}
            onClose={closePicker}
          >
            {searchable ? (
              <input
                type="search"
                className="af-full-picker__search"
                aria-label={`搜索${label}`}
                placeholder="搜索选项"
                value={keyword}
                onChange={(event) => setKeyword(event.currentTarget.value)}
              />
            ) : null}
            <div role="listbox" aria-label={label} className="af-full-picker__list">
              {visibleOptions.map((option) => {
                const active = option.isOther ? otherSelected : !otherSelected && selected === option.value;
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
                      if (option.isOther) {
                        setOtherSelected(true);
                        setSelected(null);
                        props.onValueChange(props.node.id, undefined);
                      } else {
                        setOtherSelected(false);
                        setSelected(option.value);
                        props.onValueChange(props.node.id, option.value);
                      }
                      closePicker();
                    }}
                  >
                    <span
                      className="af-full-picker__avatar af-full-picker__avatar--choice"
                      aria-hidden="true"
                      style={useColor && option.color ? { background: option.color } : undefined}
                    >
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
              {visibleOptions.length === 0 ? (
                <p className="af-full-picker__empty" role="status">没有匹配的选项</p>
              ) : null}
            </div>
          </MobileSelectionPopup>
        </>
        )
      ) : props.mode !== 'readonly' ? (
        <div className="af-field__empty-options">暂无可选项</div>
      ) : null}
      {props.mode !== 'readonly' && otherOption && otherSelected ? (
        <Input
          className="af-control"
          aria-label={`${label}其他内容`}
          placeholder="请输入"
          value={selected == null ? '' : String(selected)}
          onChange={(next) => {
            const text = next.trim();
            setSelected(text || null);
            props.onValueChange(props.node.id, text || undefined);
          }}
          style={{ marginTop: 8 }}
        />
      ) : null}
    </FieldShell>
  );

  function closePicker() {
    setKeyword('');
    setVisible(false);
  }

  function clearSelection() {
    setSelected(null);
    setOtherSelected(false);
    props.onValueChange(props.node.id, undefined);
    closePicker();
  }
}

function selectedValue(value: unknown) {
  if (value === '') {
    return null;
  }
  return typeof value === 'string' || typeof value === 'number' ? value : null;
}
