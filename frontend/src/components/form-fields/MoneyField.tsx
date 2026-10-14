import { InputNumber } from 'antd';
import type { FieldType } from '../../registry/types';

export const MoneyField: FieldType = {
  type: 'money',
  label: '金额',
  icon: 'dollar',
  defaultProps: { min: 0, precision: 2, required: false, prefix: '¥' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </div>
      <InputNumber
        disabled={mode !== 'runtime-fill'}
        value={value}
        min={node.props?.min}
        max={node.props?.max}
        precision={node.props?.precision}
        prefix={node.props?.prefix ?? '¥'}
        placeholder={node.props?.placeholder}
        onChange={(v) => onChange?.(v)}
        style={{ width: '100%' }}
      />
    </div>
  ),
};
