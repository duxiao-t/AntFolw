import { Input, Select } from 'antd';
import { useEffect, useState } from 'react';
import type { FieldType } from '../../registry/types';
import {
  customValuesFrom,
  normalizeSelectOptions,
  optionForSelect,
  selectOptionNode,
  visibleSelectOptions,
  OTHER_OPTION_VALUE,
} from './selectFieldShared';

export const SelectField: FieldType = {
  type: 'select',
  label: '下拉单选',
  icon: 'unordered-list',
  defaultProps: {
    required: false,
    options: [
      { id: 'option_1', label: '选项1', value: 'option_1' },
      { id: 'option_2', label: '选项2', value: 'option_2' },
      { id: 'option_3', label: '选项3', value: 'option_3' },
    ],
  },
  Component: ({ node, mode, value, onChange }) => {
    const options = visibleSelectOptions(node.props?.options);
    const allOptions = normalizeSelectOptions(node.props?.options);
    const useColor = node.props?.enableOptionColor === true;
    const selectOptions = options.map((option) => optionForSelect(option, useColor));
    const otherOption = options.find((option) => option.isOther);
    const currentValue = value as string | number | undefined;
    const inferredOther = Boolean(
      otherOption &&
      (customValuesFrom(currentValue, allOptions, false).length > 0 || currentValue === OTHER_OPTION_VALUE),
    );
    const [otherSelected, setOtherSelected] = useState(inferredOther);
    useEffect(() => {
      if (!otherOption) setOtherSelected(false);
      else if (currentValue != null && currentValue !== '') setOtherSelected(inferredOther);
    }, [currentValue, inferredOther, otherOption]);
    const otherActive = Boolean(otherOption && otherSelected);
    const selectValue = otherActive ? OTHER_OPTION_VALUE : currentValue;
    const otherText = customValuesFrom(currentValue, allOptions, false)[0];

    const updateOther = (next: string) => {
      onChange?.(next.trim() ? next : undefined);
    };

    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <Select
          disabled={mode !== 'runtime-fill'}
          value={selectValue}
          onChange={(nextValue) => {
            if (nextValue === OTHER_OPTION_VALUE) {
              setOtherSelected(true);
              onChange?.(undefined);
              return;
            }
            setOtherSelected(false);
            onChange?.(nextValue);
          }}
          options={selectOptions}
          placeholder={node.props?.placeholder}
          allowClear={node.props?.allowClear !== false}
          showSearch={!!node.props?.showSearch}
          optionRender={(option: any) => option.data?.renderedLabel ?? option.label}
          labelRender={(option: any) => {
            const selected = selectOptions.find((item) => item.value === option.value)?.option;
            return selected ? selectOptionNode(selected, useColor) : option.label;
          }}
          onClear={() => {
            setOtherSelected(false);
            onChange?.(undefined);
          }}
          style={{ width: '100%' }}
        />
        {otherOption && otherActive && (
          <Input
            aria-label={`${node.label ?? '其他'}自定义内容`}
            disabled={mode !== 'runtime-fill'}
            value={otherText == null ? '' : String(otherText)}
            placeholder="请输入"
            onChange={(event) => updateOther(event.target.value)}
            style={{ marginTop: 8 }}
          />
        )}
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => {
    const opts = normalizeSelectOptions(node.props?.options);
    const updateOpts = (next: any[]) => onChange({ ...node, props: { ...node.props, options: next } });
    return (
      <div style={{ padding: 16, display: 'grid', gap: 8 }}>
        <div>标签</div>
        <Input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })} />
        <div>选项（每行一条：value|label，多行）</div>
        <textarea
          rows={5}
          value={opts.map((o) => `${o.value}|${o.label}`).join('\n')}
          style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }}
          onChange={(e) => {
            const next = e.target.value
              .split('\n')
              .map((line) => line.trim())
              .filter(Boolean)
              .map((line) => {
                const [v, l] = line.split('|');
                return { value: v ?? '', label: l ?? v ?? '' };
              });
            updateOpts(next);
          }}
        />
      </div>
    );
  },
};
