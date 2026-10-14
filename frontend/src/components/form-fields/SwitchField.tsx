import { Switch } from 'antd';
import type { FieldType } from '../../registry/types';

/** 开关：值是 boolean，**只有 `=== true` 算开**（与移动端 `SwitchField.tsx` 同一判据）。 */
export const SwitchField: FieldType = {
  type: 'switch',
  label: '开关',
  icon: 'switch',
  defaultProps: { required: false, checkedText: '是', uncheckedText: '否' },
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
