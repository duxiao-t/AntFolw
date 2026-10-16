import { Space } from 'antd';
import { PanelField } from './panelField';
import { InputNumber } from 'antd';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import { useLinkedValue } from './dynamicOptions';

export const NumberField: FieldType = {
  type: 'number',
  label: '数字',
  icon: 'field-number',
  defaultProps: { min: 0, max: 1000000, precision: 0, required: false },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="最小值">
          <InputNumber
            style={{ width: '100%' }}
            value={props.min}
            placeholder="不限制"
            onChange={(value) => updateProps({ min: value ?? undefined })}
          />
        </PanelField>
        <PanelField label="最大值">
          <InputNumber
            style={{ width: '100%' }}
            value={props.max}
            placeholder="不限制"
            onChange={(value) => updateProps({ max: value ?? undefined })}
          />
        </PanelField>
        <PanelField label="小数位">
          <InputNumber
            min={0}
            max={8}
            style={{ width: '100%' }}
            value={props.precision ?? 0}
            onChange={(value) => updateProps({ precision: value ?? 0 })}
          />
        </PanelField>
        <PanelField label="步长">
          <InputNumber
            min={0}
            style={{ width: '100%' }}
            value={props.step ?? 1}
            onChange={(value) => updateProps({ step: value ?? 1 })}
          />
        </PanelField>
      </Space>
    );
  },
  Component: (props) => <LinkedNumberField {...props} />,
};

function LinkedNumberField({ node, mode, value, onChange, values, optionContext }: FieldComponentProps) {
  useLinkedValue(node, values, mode === 'runtime-fill' ? optionContext : undefined, onChange);
  return (
    <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
      <InputNumber disabled={mode !== 'runtime-fill'} value={value} min={node.props?.min} max={node.props?.max}
        precision={node.props?.precision} step={node.props?.step} placeholder={node.props?.placeholder}
        onChange={(v) => onChange?.(v)} style={{ width: '100%' }} />
    </div>
  );
}
