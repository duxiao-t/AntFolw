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
  selectDisplayStyle,
} from './fieldShared';
import { MobileSelectionPopup } from './MobileSelectionPopup';
import { DynamicSelectField } from './DynamicSelectField';
import { isBoundOptionSource } from './dynamicOptions';
import { PickerOptionList, PickerSearchInput, PickerTrigger } from './SelectPicker';

const OTHER_OPTION_VALUE = '__antflow_other__';

export function MultiSelectField(props: MobileFieldProps) {
  if (isBoundOptionSource(props.node.props)) return <DynamicSelectField {...props} multiple />;
  return <StaticMultiSelectField {...props} />;
}

function StaticMultiSelectField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const values = useMemo(() => arrayValue(props.value), [props.value]);
  const [selected, setSelected] = useState<Array<string | number>>(values);
  const [draftSelected, setDraftSelected] = useState<Array<string | number>>(values);
  const [visible, setVisible] = useState(false);
  const [keyword, setKeyword] = useState('');
  const options = fieldOptions(props.node);
  const searchable = props.node.props?.showSearch === true;
  const clearable = props.node.props?.allowClear !== false;
  const displayStyle = selectDisplayStyle(props.node);
  const maxCount = typeof props.node.props?.maxCount === 'number'
    ? props.node.props.maxCount
    : undefined;
  const allOptions = allFieldOptions(props.node);
  const useColor = props.node.props?.enableOptionColor === true;
  const otherOption = options.find((option) => option.isOther);
  const hasOtherOption = Boolean(otherOption);
  const standardValues = allOptions.filter((option) => !option.isOther).map((option) => option.value);
  const customValues = values.filter((item) => !standardValues.includes(item));
  const [otherSelected, setOtherSelected] = useState(Boolean(otherOption && customValues.length > 0));

  useEffect(() => {
    setSelected(values);
  }, [values]);

  useEffect(() => {
    if (!hasOtherOption) setOtherSelected(false);
    else if (customValues.length > 0) setOtherSelected(true);
  }, [customValues.length, hasOtherOption]);

  const selectedLabels = options
    .filter((option) => selected.includes(option.value))
    .map((option) => option.label);
  const selectedCustomValues = selected.filter((item) => !standardValues.includes(item));
  if (otherSelected) selectedLabels.push('其他');
  const placeholder = String(props.node.props?.placeholder ?? `选择${label}`);
  const visibleOptions = useMemo(() => {
    const query = keyword.trim().toLocaleLowerCase();
    return query
      ? options.filter((option) => option.label.toLocaleLowerCase().includes(query))
      : options;
  }, [keyword, options]);

  return (
    <FieldShell
      node={props.node}
      label={label}
      required={isRequired(props.node)}
      error={fieldError(props)}
      summary={props.mode === 'readonly' ? <div className="af-field__summary">{selectedLabels.join('、') || '未填写'}</div> : undefined}
    >
      {props.mode !== 'readonly' && options.length > 0 ? (
        displayStyle !== 'dropdown' ? (
          <InlineFieldOptions
            label={label}
            displayStyle={displayStyle}
            options={options.map((option) => option.isOther
              ? { ...option, value: OTHER_OPTION_VALUE }
              : option)}
            selectedValues={[
              ...selected.filter((item) => standardValues.includes(item)),
              ...(otherSelected ? [OTHER_OPTION_VALUE] : []),
            ]}
            multiple
            useColor={useColor}
            maxCount={maxCount}
            onToggle={(option, active) => {
              const standard = selected.filter((item) => standardValues.includes(item));
              if (option.isOther) {
                const next = active ? standard : [...standard, ...selectedCustomValues];
                setOtherSelected(!active);
                setSelected(next);
                props.onValueChange(props.node.id, next);
                return;
              }
              const nextStandard = active
                ? standard.filter((item) => item !== option.value)
                : [...standard, option.value];
              const next = otherSelected
                ? [...nextStandard, ...selectedCustomValues]
                : nextStandard;
              setSelected(next);
              props.onValueChange(props.node.id, next);
            }}
          />
        ) : (
        <>
          <PickerTrigger labels={selectedLabels} placeholder={placeholder}
            onClick={() => {
              setKeyword('');
              setDraftSelected([
                ...selected.filter((item) => standardValues.includes(item)),
                ...(otherSelected ? [OTHER_OPTION_VALUE] : []),
              ]);
              setVisible(true);
            }} />
          <MobileSelectionPopup
            visible={visible}
            title={`选择${label}`}
            subtitle={`已选 ${draftSelected.length} 项`}
            presentation="sheet"
            headerAction={clearable && draftSelected.length > 0 ? (
              <button
                type="button"
                className="af-full-picker__clear"
                onClick={() => setDraftSelected([])}
              >
                清空
              </button>
            ) : undefined}
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
            {searchable ? (
              <PickerSearchInput label={`搜索${label}`} placeholder="搜索选项"
                value={keyword} onChange={setKeyword} />
            ) : null}
            <PickerOptionList label={label} multiple useColor={useColor}
              options={visibleOptions.map((option) => {
                const value = option.isOther ? OTHER_OPTION_VALUE : option.value;
                return {
                  value,
                  label: option.label,
                  color: option.color,
                  disabled: option.disabled,
                  selected: draftSelected.includes(value),
                };
              })}
              onSelect={(option) => toggleDraft(option.value)} />
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
          value={selectedCustomValues[0] == null ? '' : String(selectedCustomValues[0])}
          onChange={(text) => {
            const standard = selected.filter((item) => !selectedCustomValues.includes(item));
            const next = text.trim() ? [...standard, text] : standard;
            setSelected(next);
            props.onValueChange(props.node.id, next);
          }}
          style={{ marginTop: 8 }}
        />
      ) : null}
    </FieldShell>
  );

  function closePicker() {
    setKeyword('');
    setDraftSelected([
      ...selected.filter((item) => standardValues.includes(item)),
      ...(otherSelected ? [OTHER_OPTION_VALUE] : []),
    ]);
    setVisible(false);
  }

  function confirmPicker() {
    const hasOther = draftSelected.includes(OTHER_OPTION_VALUE);
    const nextStandard = draftSelected.filter((item) => item !== OTHER_OPTION_VALUE);
    const next = hasOther ? [...nextStandard, ...customValues] : nextStandard;
    setOtherSelected(hasOther);
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
