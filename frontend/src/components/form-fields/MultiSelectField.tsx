import { Input, Select } from 'antd';
import { useEffect, useState } from 'react';
import type { FieldType } from '../../registry/types';
import { SelectField } from './SelectField';
import {
  customValuesFrom,
  InlineSelectOptions,
  normalizeSelectDisplayStyle,
  normalizeSelectOptions,
  optionForSelect,
  selectedOptionSummary,
  selectOptionNode,
  visibleSelectOptions,
  OTHER_OPTION_VALUE,
} from './selectFieldShared';

export const MultiSelectField: FieldType = {
  type: 'multi_select',
  label: '下拉多选',
  icon: 'unordered-list',
  defaultProps: {
    required: false,
    displayStyle: 'dropdown',
    options: [
      { id: 'option_1', label: '选项1', value: 'option_1' },
      { id: 'option_2', label: '选项2', value: 'option_2' },
      { id: 'option_3', label: '选项3', value: 'option_3' },
    ],
  },
  Component: ({ node, mode, value, onChange }) => {
    const options = visibleSelectOptions(node.props?.options);
    const allOptions = normalizeSelectOptions(node.props?.options);
    const displayStyle = normalizeSelectDisplayStyle(node.props?.displayStyle);
    const useColor = node.props?.enableOptionColor === true;
    const selectOptions = options.map((option) => optionForSelect(option, useColor));
    const otherOption = options.find((option) => option.isOther);
    const currentValues = Array.isArray(value) ? value : [];
    const customValues = customValuesFrom(currentValues, allOptions, true);
    const [otherSelected, setOtherSelected] = useState(Boolean(otherOption && customValues.length > 0));
    useEffect(() => {
      if (!otherOption) setOtherSelected(false);
      else if (customValues.length > 0) setOtherSelected(true);
    }, [customValues.length, otherOption]);
    const otherActive = Boolean(otherOption && otherSelected);
    const selectedValues = [
      ...currentValues.filter((item) => !customValues.includes(item)),
      ...(otherActive ? [OTHER_OPTION_VALUE] : []),
    ];
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        {mode === 'readonly' ? (
          <div className="form-select-readonly">
            {selectedOptionSummary(currentValues, allOptions, true)}
          </div>
        ) : displayStyle === 'dropdown' ? (
          <Select
            mode="multiple"
            disabled={mode !== 'runtime-fill'}
            options={selectOptions}
            placeholder={node.props?.placeholder}
            allowClear={node.props?.allowClear !== false}
            showSearch={!!node.props?.showSearch}
            maxCount={node.props?.maxCount}
            value={selectedValues}
            onChange={(nextValues) => {
              const next = Array.isArray(nextValues) ? nextValues : [];
              const hasOther = next.includes(OTHER_OPTION_VALUE);
              const standard = next.filter((item) => item !== OTHER_OPTION_VALUE);
              setOtherSelected(hasOther);
              onChange?.(hasOther ? [...standard, ...customValues] : standard);
            }}
            optionRender={(option: any) => option.data?.renderedLabel ?? option.label}
            labelRender={(option: any) => {
              const selected = selectOptions.find((item) => item.value === option.value)?.option;
              return selected ? selectOptionNode(selected, useColor) : option.label;
            }}
            style={{ width: '100%' }}
          />
        ) : (
          <InlineSelectOptions
            label={node.label ?? '下拉多选'}
            displayStyle={displayStyle}
            options={options}
            selectedValues={selectedValues}
            multiple
            useColor={useColor}
            disabled={mode !== 'runtime-fill'}
            maxCount={node.props?.maxCount}
            onToggle={(option, selected) => {
              if (option.isOther) {
                setOtherSelected(!selected);
                const standard = currentValues.filter((item) => !customValues.includes(item));
                onChange?.(selected ? standard : [...standard, ...customValues]);
                return;
              }
              const standard = currentValues.filter((item) => !customValues.includes(item));
              const next = selected
                ? standard.filter((item) => item !== option.value)
                : [...standard, option.value];
              onChange?.(otherActive ? [...next, ...customValues] : next);
            }}
          />
        )}
        {mode !== 'readonly' && otherOption && otherActive && (
          <Input
            aria-label={`${node.label ?? '其他'}自定义内容`}
            disabled={mode !== 'runtime-fill'}
            value={customValues[0] == null ? '' : String(customValues[0])}
            placeholder="请输入"
            onChange={(event) => {
              const text = event.target.value;
              const standard = currentValues.filter((item) => !customValues.includes(item));
              onChange?.(text.trim() ? [...standard, text] : standard);
            }}
            style={{ width: '100%', marginTop: 8 }}
          />
        )}
      </div>
    );
  },
  ConfigPanel: SelectField.ConfigPanel,
};
