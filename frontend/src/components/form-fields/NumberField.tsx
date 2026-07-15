import { InputNumber } from 'antd';
import type { FieldType } from '../../registry/types';

export const NumberField: FieldType = {
  type: 'number',
  label: '数字',
  icon: 'field-number',
  defaultProps: { min: 0, max: 1000000, precision: 0, required: false },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <InputNumber
        disabled={mode !== 'runtime-fill'}
        value={value}
        min={node.props?.min}
        max={node.props?.max}
        precision={node.props?.precision}
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
      <label>最小值</label>
      <input type="number" value={node.props?.min ?? 0} onChange={(e) => onChange({ ...node, props: { ...node.props, min: Number(e.target.value) } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>最大值</label>
      <input type="number" value={node.props?.max ?? 1000000} onChange={(e) => onChange({ ...node, props: { ...node.props, max: Number(e.target.value) } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>小数位</label>
      <input type="number" value={node.props?.precision ?? 0} onChange={(e) => onChange({ ...node, props: { ...node.props, precision: Number(e.target.value) } })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
