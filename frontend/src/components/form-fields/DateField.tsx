import { DatePicker } from 'antd';
import type { FieldType } from '../../registry/types';

export const DateField: FieldType = {
  type: 'date',
  label: '日期',
  icon: 'calendar',
  defaultProps: { required: false, format: 'YYYY-MM-DD' },
  Component: ({ node, mode, value, onChange }) => {
    const format = node.props?.format ?? 'YYYY-MM-DD';
    const resolvedValue =
      value ??
      (mode === 'runtime-fill' && node.props?.defaultNow
        ? (window as any).dayjs?.().format(format)
        : undefined);
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <DatePicker
          disabled={mode !== 'runtime-fill'}
          value={resolvedValue ? (window as any).dayjs?.(resolvedValue) : undefined}
          onChange={(d: any) => onChange?.(d ? d.format(format) : undefined)}
          placeholder={node.props?.placeholder}
          format={format}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
