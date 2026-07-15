import { InputNumber } from 'antd';
import type { FieldType } from '../../registry/types';

export const MoneyField: FieldType = {
  type: 'money',
  label: '金额',
  icon: 'dollar',
  defaultProps: { min: 0, precision: 2, required: false, prefix: '¥' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <InputNumber
        disabled={mode !== 'runtime-fill'}
        value={value}
        min={node.props?.min}
        precision={node.props?.precision}
        prefix={node.props?.prefix ?? '¥'}
        onChange={(v) => onChange?.(v)}
        style={{ width: '100%' }}
      />
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>小数位</label>
      <input type="number" value={node.props?.precision ?? 2} onChange={(e) => onChange({ ...node, props: { ...node.props, precision: Number(e.target.value) } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
