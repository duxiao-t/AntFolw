import { Space, Input } from 'antd';
import { PanelField } from './panelField';
import { InputNumber } from 'antd';
import type { FieldType } from '../../registry/types';

export const MoneyField: FieldType = {
  type: 'money',
  label: '金额',
  icon: 'dollar',
  defaultProps: { min: 0, precision: 2, required: false, prefix: '¥' },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="前缀">
          <Input
            value={props.prefix ?? '¥'}
            onChange={(event) => updateProps({ prefix: event.target.value })}
          />
        </PanelField>
        <PanelField label="最小金额">
          <InputNumber
            style={{ width: '100%' }}
            value={props.min ?? 0}
            onChange={(value) => updateProps({ min: value ?? 0 })}
          />
        </PanelField>
        <PanelField label="最大金额">
          <InputNumber
            style={{ width: '100%' }}
            value={props.max}
            onChange={(value) => updateProps({ max: value })}
          />
        </PanelField>
        <PanelField label="小数位">
          <InputNumber
            min={0}
            max={8}
            style={{ width: '100%' }}
            value={props.precision ?? 2}
            onChange={(value) => updateProps({ precision: value ?? 2 })}
          />
        </PanelField>
      </Space>
    );
  },
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
