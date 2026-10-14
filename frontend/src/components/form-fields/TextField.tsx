import { Input } from 'antd';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import { useLinkedValue } from './dynamicOptions';

export const TextField: FieldType = {
  type: 'text',
  label: '单行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 255, placeholder: '请输入' },
  Component: (props) => <LinkedTextField {...props} />,
};

function LinkedTextField(props: FieldComponentProps) {
  const { node, mode, value, onChange, values, optionContext } = props;
  useLinkedValue(node, values, mode === 'runtime-fill' ? optionContext : undefined, onChange);
  return (
    <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
      <Input
        type={node.props?.inputType ?? 'text'}
        disabled={mode !== 'runtime-fill'}
        value={value ?? ''}
        maxLength={node.props?.maxLength}
        minLength={node.props?.minLength}
        pattern={node.props?.pattern || undefined}
        placeholder={node.props?.placeholder}
        onChange={(e) => onChange?.(node.props?.trim ? e.target.value.trimStart() : e.target.value)}
        onBlur={(e) => node.props?.trim ? onChange?.(e.target.value.trim()) : undefined}
      />
    </div>
  );
}
