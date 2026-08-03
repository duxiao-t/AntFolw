import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

export const TextField: FieldType = {
  type: 'text',
  label: '单行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 255, placeholder: '请输入' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <div style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </div>
      <Input
        type={node.props?.inputType ?? 'text'}
        disabled={mode !== 'runtime-fill'}
        value={value ?? ''}
        maxLength={node.props?.maxLength}
        minLength={node.props?.minLength}
        pattern={node.props?.pattern || undefined}
        placeholder={node.props?.placeholder}
        onChange={(e) =>
          onChange?.(node.props?.trim ? e.target.value.trimStart() : e.target.value)
        }
        onBlur={(e) =>
          node.props?.trim ? onChange?.(e.target.value.trim()) : undefined
        }
      />
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <Input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })} />
      <div>占位</div>
      <Input value={node.props?.placeholder ?? ''}
        onChange={(e) => onChange({ ...node, props: { ...node.props, placeholder: e.target.value } })} />
      <div>最大长度</div>
      <Input type="number" value={node.props?.maxLength ?? 255}
        onChange={(e) => onChange({ ...node, props: { ...node.props, maxLength: Number(e.target.value) } })} />
      <label>
        <input type="checkbox" checked={!!node.props?.required}
          onChange={(e) => onChange({ ...node, props: { ...node.props, required: e.target.checked } })} />
        {' '}必填
      </label>
    </div>
  ),
};
