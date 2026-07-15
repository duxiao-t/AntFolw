import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

export const TextareaField: FieldType = {
  type: 'textarea',
  label: '多行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 2000, rows: 4, placeholder: '请输入' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <Input.TextArea
        disabled={mode !== 'runtime-fill'}
        value={value ?? ''}
        rows={node.props?.rows ?? 4}
        maxLength={node.props?.maxLength}
        placeholder={node.props?.placeholder}
        onChange={(e) => onChange?.(e.target.value)}
      />
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <Input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })} />
      <label>行数</label>
      <Input type="number" value={node.props?.rows ?? 4}
        onChange={(e) => onChange({ ...node, props: { ...node.props, rows: Number(e.target.value) } })} />
      <label>最大长度</label>
      <Input type="number" value={node.props?.maxLength ?? 2000}
        onChange={(e) => onChange({ ...node, props: { ...node.props, maxLength: Number(e.target.value) } })} />
      <label>
        <input type="checkbox" checked={!!node.props?.required}
          onChange={(e) => onChange({ ...node, props: { ...node.props, required: e.target.checked } })} />
        {' '}必填
      </label>
    </div>
  ),
};
