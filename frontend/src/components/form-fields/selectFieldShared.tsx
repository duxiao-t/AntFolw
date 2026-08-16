import type { ReactNode } from 'react';
import {
  normalizeSelectOptions,
  OTHER_OPTION_VALUE,
  visibleSelectOptions,
  type SelectOption,
  type SelectOptionValue,
} from '../../registry/selectOptions';
import './select-fields.less';

export { normalizeSelectOptions, OTHER_OPTION_VALUE, visibleSelectOptions };
export type { SelectOption, SelectOptionValue };

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
