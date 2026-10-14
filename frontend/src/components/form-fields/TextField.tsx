import { Checkbox, Input, InputNumber, Select, Space } from 'antd';
import type { FieldComponentProps, FieldType } from '../../registry/types';
import { useLinkedValue } from './dynamicOptions';
import { PanelField } from './panelField';

export const TextField: FieldType = {
  type: 'text',
  label: '单行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 255, placeholder: '请输入' },
  Component: (props) => <LinkedTextField {...props} />,
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="输入类型">
          <Select
            value={props.inputType ?? 'text'}
            options={[
              { label: '文本', value: 'text' },
              { label: '数字', value: 'number' },
              { label: '邮箱', value: 'email' },
              { label: '手机号', value: 'tel' },
            ]}
            onChange={(value) => updateProps({ inputType: value })}
          />
        </PanelField>
        <PanelField label="最大长度">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxLength ?? 255}
            onChange={(value) => updateProps({ maxLength: value ?? 255 })}
          />
        </PanelField>
        <Checkbox
          checked={!!props.trim}
          onChange={(event) => updateProps({ trim: event.target.checked })}
        >
          自动去除首尾空格
        </Checkbox>
      </Space>
    );
  },
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
