import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

export const TextField: FieldType = {
  type: 'text',
  label: '单行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 255, placeholder: '请输入' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <Input
        disabled={mode !== 'runtime-fill'}
        value={value ?? ''}
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
      <label>占位</label>
      <Input value={node.props?.placeholder ?? ''}
        onChange={(e) => onChange({ ...node, props: { ...node.props, placeholder: e.target.value } })} />
      <label>最大长度</label>
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
