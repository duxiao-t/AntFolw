import { Radio } from 'antd';
import type { FieldType } from '../../registry/types';
import { choiceOptions, choiceReadonlyText, optionColor } from './choiceFieldOptions';
import { SelectField } from './SelectField';

/** 单选：值是该选项的 `value`（字符串或数字），与移动端 `RadioField.tsx` 同义。 */
export const RadioField: FieldType = {
  type: 'radio',
  label: '单选',
  icon: 'radio',
  defaultProps: {
    required: false,
    options: [
      { value: 'a', label: '选项 A' },
      { value: 'b', label: '选项 B' },
    ],
  },
  Component: ({ node, mode, value, onChange }) => {
    const options = choiceOptions(node);
    const selected = typeof value === 'string' || typeof value === 'number' ? value : undefined;
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
        {mode === 'readonly' ? (
          <div style={{ color: 'rgba(0,0,0,0.65)' }}>{choiceReadonlyText(options, value)}</div>
        ) : (
          <Radio.Group
            disabled={mode !== 'runtime-fill'}
            value={selected}
            onChange={(event) => onChange?.(event.target.value)}
          >
            {options.map((option) => (
              <Radio key={String(option.value)} value={option.value}>
                <span style={optionColor(node.props, option)}>{option.label}</span>
              </Radio>
            ))}
          </Radio.Group>
        )}
      </div>
    );
  },
  // 设计器不读 ConfigPanel（Inspector 走自己的 renderComponentSettings），真正的选项编辑器在
  // Inspector 的 `case 'radio'` 分支里。这里给一个是为了满足 FieldType 的必填字段，与
  // MultiSelectField.tsx 复用 SelectField.ConfigPanel 是同一个先例。
  ConfigPanel: SelectField.ConfigPanel,
};
