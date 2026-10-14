import { Space } from 'antd';
import { SelectOptionsEditor } from './SelectOptionsEditor';
import { Radio } from 'antd';
import type { FieldType } from '../../registry/types';
import { choiceOptions, choiceReadonlyText, optionColor } from './choiceFieldOptions';

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
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <SelectOptionsEditor
          value={props.options}
          multiple={node.type === 'checkbox'}
          defaultValue={props.defaultValue}
          enableColors={props.enableOptionColor === true}
          onChange={(options) => updateProps({ options })}
          onDefaultChange={(defaultValue) => updateProps({ defaultValue })}
          onEnableColorsChange={(enableOptionColor, options) =>
            updateProps({ enableOptionColor, options })
          }
        />
      </Space>
    );
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
};
