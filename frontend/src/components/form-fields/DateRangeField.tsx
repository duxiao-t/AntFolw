import { DatePicker } from 'antd';
import type { FieldType } from '../../registry/types';

export const DateRangeField: FieldType = {
  type: 'date_range',
  label: '日期范围',
  icon: 'calendar',
  defaultProps: { required: false, format: 'YYYY-MM-DD' },
  Component: ({ node, mode, value, onChange }) => {
    const v = Array.isArray(value) ? value : [];
    const dayjs = (window as any).dayjs;
    return (
      <div data-field-id={node.id}>
        <label style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </label>
        <DatePicker.RangePicker
          disabled={mode !== 'runtime-fill'}
          value={v.length === 2 && dayjs ? [dayjs(v[0]), dayjs(v[1])] : undefined}
          onChange={(d: any) => onChange?.(d ? [d[0].format('YYYY-MM-DD'), d[1].format('YYYY-MM-DD')] : undefined)}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
