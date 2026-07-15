import { DatePicker } from 'antd';
import type { FieldType } from '../../registry/types';

export const DateField: FieldType = {
  type: 'date',
  label: '日期',
  icon: 'calendar',
  defaultProps: { required: false, format: 'YYYY-MM-DD' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <DatePicker
        disabled={mode !== 'runtime-fill'}
        value={value ? (window as any).dayjs?.(value) : undefined}
        onChange={(d: any) => onChange?.(d ? d.format(node.props?.format ?? 'YYYY-MM-DD') : undefined)}
        style={{ width: '100%' }}
      />
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
