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
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <DatePicker.RangePicker
          disabled={mode !== 'runtime-fill'}
          value={v.length === 2 && dayjs ? [dayjs(v[0]), dayjs(v[1])] : undefined}
          onChange={(d: any) => onChange?.(d ? [d[0].format(node.props?.format ?? 'YYYY-MM-DD'), d[1].format(node.props?.format ?? 'YYYY-MM-DD')] : undefined)}
          placeholder={
            node.props?.placeholder
              ? [node.props.placeholder, node.props.placeholder]
              : undefined
          }
          format={node.props?.format ?? 'YYYY-MM-DD'}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
};
