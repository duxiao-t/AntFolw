import { Select } from 'antd';
import type { FieldType } from '../../registry/types';
import { SelectField } from './SelectField';

export const MultiSelectField: FieldType = {
  type: 'multi_select',
  label: '下拉多选',
  icon: 'unordered-list',
  defaultProps: {
    required: false,
    options: [] as { label: string; value: string }[],
  },
  Component: ({ node, mode, value, onChange }) => {
    const opts: any[] = node.props?.options ?? [];
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <Select
          mode="multiple"
          disabled={mode !== 'runtime-fill'}
          value={value ?? []}
          onChange={(v) => onChange?.(v)}
          options={opts}
          placeholder={node.props?.placeholder}
          allowClear={node.props?.allowClear !== false}
          showSearch={!!node.props?.showSearch}
          maxCount={node.props?.maxCount}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: SelectField.ConfigPanel,
};
