import { Space, Select, Input } from 'antd';
import { PanelField } from './panelField';
import { DatePicker } from 'antd';
import type { FieldType } from '../../registry/types';

export const DateField: FieldType = {
  type: 'date',
  label: '日期',
  icon: 'calendar',
  defaultProps: { required: false, format: 'YYYY-MM-DD' },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="日期格式">
          <Select
            value={props.format ?? 'YYYY-MM-DD'}
            options={[
              { label: 'YYYY-MM-DD', value: 'YYYY-MM-DD' },
              { label: 'YYYY/MM/DD', value: 'YYYY/MM/DD' },
              { label: 'YYYY-MM-DD HH:mm', value: 'YYYY-MM-DD HH:mm' },
            ]}
            onChange={(value) => updateProps({ format: value })}
          />
        </PanelField>
        <PanelField label="最早可选日期">
          <Input
            value={props.minDate ?? ''}
            placeholder="YYYY-MM-DD"
            onChange={(event) => updateProps({ minDate: event.target.value })}
          />
        </PanelField>
        <PanelField label="最晚可选日期">
          <Input
            value={props.maxDate ?? ''}
            placeholder="YYYY-MM-DD"
            onChange={(event) => updateProps({ maxDate: event.target.value })}
          />
        </PanelField>
      </Space>
    );
  },
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
};
