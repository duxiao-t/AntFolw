import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

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
};
