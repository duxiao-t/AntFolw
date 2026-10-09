import { Checkbox } from 'antd';
import type { FieldType } from '../../registry/types';
import { choiceOptions, choiceReadonlyText, optionColor } from './choiceFieldOptions';
import { SelectField } from './SelectField';

/** 多选：值是选中项 `value` 的数组，与移动端 `CheckboxField.tsx` 同义。 */
export const CheckboxField: FieldType = {
  type: 'checkbox',
  label: '多选',
  icon: 'checkbox',
  defaultProps: {
    required: false,
    options: [
      { value: 'a', label: '选项 A' },
      { value: 'b', label: '选项 B' },
    ],
  },
  Component: ({ node, mode, value, onChange }) => {
    const options = choiceOptions(node);
    const selected = Array.isArray(value)
      ? value.filter((item): item is string | number => typeof item === 'string' || typeof item === 'number')
      : [];
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
        {mode === 'readonly' ? (
          <div style={{ color: 'rgba(0,0,0,0.65)' }}>
            {selected.length === 0
              ? '未填写'
              : selected.map((item) => choiceReadonlyText(options, item)).join('、')}
          </div>
        ) : (
          <Checkbox.Group
            disabled={mode !== 'runtime-fill'}
            value={selected}
            onChange={(next) => onChange?.(next)}
          >
            {options.map((option) => (
              <Checkbox key={String(option.value)} value={option.value}>
                <span style={optionColor(node.props, option)}>{option.label}</span>
              </Checkbox>
            ))}
          </Checkbox.Group>
        )}
      </div>
    );
  },
  // 同 RadioField：真正的选项编辑器在 Inspector 的 `case 'checkbox'` 分支。
  ConfigPanel: SelectField.ConfigPanel,
};
