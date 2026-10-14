import { Checkbox, Input, InputNumber, Space } from 'antd';
import type { FieldType } from '../../registry/types';
import { PanelField } from './panelField';

export const TextareaField: FieldType = {
  type: 'textarea',
  label: '多行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 2000, rows: 4, placeholder: '请输入' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </div>
      <Input.TextArea
        disabled={mode !== 'runtime-fill'}
        value={value ?? ''}
        rows={node.props?.rows ?? 4}
        maxLength={node.props?.maxLength}
        placeholder={node.props?.placeholder}
        showCount={!!node.props?.showCount}
        onChange={(e) => onChange?.(e.target.value)}
      />
    </div>
  ),
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="行数">
          <InputNumber
            min={2}
            max={12}
            style={{ width: '100%' }}
            value={props.rows ?? 4}
            onChange={(value) => updateProps({ rows: value ?? 4 })}
          />
        </PanelField>
        <PanelField label="最大长度">
          <InputNumber
            min={1}
            style={{ width: '100%' }}
            value={props.maxLength ?? 2000}
            onChange={(value) => updateProps({ maxLength: value ?? 2000 })}
          />
        </PanelField>
        <Checkbox
          checked={!!props.showCount}
          onChange={(event) => updateProps({ showCount: event.target.checked })}
        >
          显示字数统计
        </Checkbox>
      </Space>
    );
  },
};
