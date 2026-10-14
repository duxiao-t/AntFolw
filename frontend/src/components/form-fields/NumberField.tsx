import { InputNumber } from 'antd';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import { useLinkedValue } from './dynamicOptions';

export const NumberField: FieldType = {
  type: 'number',
  label: '数字',
  icon: 'field-number',
  defaultProps: { min: 0, max: 1000000, precision: 0, required: false },
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
