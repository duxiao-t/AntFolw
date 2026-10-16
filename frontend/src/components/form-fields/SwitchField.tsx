import { Space, Input } from 'antd';
import { PanelField } from './panelField';
import { Switch } from 'antd';
import type { FieldType } from '../../registry/types';

/** 开关：值是 boolean，**只有 `=== true` 算开**（与移动端 `SwitchField.tsx` 同一判据）。 */
export const SwitchField: FieldType = {
  type: 'switch',
  label: '开关',
  icon: 'switch',
  defaultProps: { required: false, checkedText: '是', uncheckedText: '否' },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="开状态文案">
          <Input
            value={props.checkedText ?? '是'}
            onChange={(event) => updateProps({ checkedText: event.target.value })}
          />
        </PanelField>
        <PanelField label="关状态文案">
          <Input
            value={props.uncheckedText ?? '否'}
            onChange={(event) => updateProps({ uncheckedText: event.target.value })}
          />
        </PanelField>
      </Space>
    );
  },
  Component: ({ node, mode, value, onChange }) => {
    const checked = value === true;
    const checkedText = String(node.props?.checkedText ?? '是');
    const uncheckedText = String(node.props?.uncheckedText ?? '否');
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
        {mode === 'readonly' ? (
          <div style={{ color: 'rgba(0,0,0,0.65)' }}>{checked ? checkedText : uncheckedText}</div>
        ) : (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
            <span>{uncheckedText}</span>
            <Switch
              disabled={mode !== 'runtime-fill'}
              checked={checked}
              onChange={(next) => onChange?.(next)}
            />
            <span>{checkedText}</span>
          </span>
        )}
      </div>
    );
  },
};
