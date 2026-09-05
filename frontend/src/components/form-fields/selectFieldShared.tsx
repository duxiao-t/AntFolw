import type { ReactNode } from 'react';
import {
  normalizeSelectDisplayStyle,
  normalizeSelectOptions,
  OTHER_OPTION_VALUE,
  visibleSelectOptions,
  type SelectOption,
  type SelectOptionValue,
  type SelectDisplayStyle,
} from '../../registry/selectOptions';
import './select-fields.less';

export {
  normalizeSelectDisplayStyle,
  normalizeSelectOptions,
  OTHER_OPTION_VALUE,
  visibleSelectOptions,
};
export type { SelectDisplayStyle, SelectOption, SelectOptionValue };

export function selectOptionText(option: SelectOption) {
  return String(option.label ?? option.value);
}

export function selectOptionNode(option: SelectOption, useColor: boolean): ReactNode {
  return (
    <span className="form-select-option-label">
      {useColor && option.color ? (
        <span
          className="form-select-option-label__color"
          style={{ backgroundColor: option.color }}
          aria-hidden="true"
        />
      ) : null}
      <span>{selectOptionText(option)}</span>
    </span>
  );
}

export function optionForSelect(option: SelectOption, useColor: boolean) {
  return {
    value: option.isOther ? OTHER_OPTION_VALUE : option.value,
    label: selectOptionText(option),
    disabled: option.disabled,
    option,
    renderedLabel: selectOptionNode(option, useColor),
  };
}

export function isStandardOptionValue(value: unknown, options: SelectOption[]) {
  return options.some((option) => !option.isOther && option.value === value);
}

export function customValuesFrom(value: unknown, options: SelectOption[], multiple: boolean): SelectOptionValue[] {
  const standard = options.filter((option) => !option.isOther).map((option) => option.value);
  const values = multiple ? (Array.isArray(value) ? value : []) : [value];
  return values.filter(
    (item): item is SelectOptionValue =>
      (typeof item === 'string' || typeof item === 'number') &&
      item !== OTHER_OPTION_VALUE &&
      !standard.includes(item),
  );
}

export function selectedOptionSummary(
  value: unknown,
  options: SelectOption[],
  multiple: boolean,
) {
  const values = multiple ? (Array.isArray(value) ? value : []) : [value];
  const labels = values.flatMap((item) => {
    if (item == null || item === '') return [];
    const option = options.find((candidate) =>
      candidate.isOther ? item === OTHER_OPTION_VALUE : candidate.value === item,
    );
    return [option ? selectOptionText(option) : String(item)];
  });
  return labels.join('、') || '未填写';
}

export function InlineSelectOptions({
  label,
  displayStyle,
  options,
  selectedValues,
  multiple,
  useColor,
  disabled,
  maxCount,
  onToggle,
}: {
  label: string;
  displayStyle: Exclude<SelectDisplayStyle, 'dropdown'>;
  options: SelectOption[];
  selectedValues: SelectOptionValue[];
  multiple: boolean;
  useColor: boolean;
  disabled: boolean;
  maxCount?: number;
  onToggle(option: SelectOption, selected: boolean): void;
}) {
  const limitReached = multiple && Number.isInteger(maxCount)
    && (maxCount as number) > 0 && selectedValues.length >= (maxCount as number);
  return (
    <div
      className={`form-select-choices form-select-choices--${displayStyle}`}
      role={multiple ? 'group' : 'radiogroup'}
    >
      {options.map((option) => {
        const optionValue = option.isOther ? OTHER_OPTION_VALUE : option.value;
        const selected = selectedValues.includes(optionValue);
        const content = (
          <>
            <span className={`form-select-choice__indicator${multiple ? ' is-checkbox' : ''}`} aria-hidden="true" />
            {useColor && option.color ? (
              <span
                className="form-select-option-label__color"
                style={{ backgroundColor: option.color }}
                aria-hidden="true"
              />
            ) : null}
            <span className="form-select-choice__label">{selectOptionText(option)}</span>
          </>
        );
        const disabledOption = disabled || option.disabled || (limitReached && !selected);
        return multiple ? (
          // biome-ignore lint/a11y/useSemanticElements: Styled choice buttons need a full-row touch target.
          <button
            key={String(option.id ?? optionValue)}
            type="button"
            role="checkbox"
            aria-checked={selected}
            aria-label={`${label}：${selectOptionText(option)}`}
            disabled={disabledOption}
            className="form-select-choice"
            title={selectOptionText(option)}
            onClick={() => onToggle(option, selected)}
          >
            {content}
          </button>
        ) : (
          // biome-ignore lint/a11y/useSemanticElements: Styled choice buttons need a full-row touch target.
          <button
            key={String(option.id ?? optionValue)}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={`${label}：${selectOptionText(option)}`}
            disabled={disabledOption}
            className="form-select-choice"
            title={selectOptionText(option)}
            onClick={() => onToggle(option, selected)}
          >
            {content}
          </button>
        );
      })}
    </div>
  );
}
