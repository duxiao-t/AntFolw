import type { FieldType } from '../../registry/types';

export const DescriptionField: FieldType = {
  type: 'description',
  label: '说明文字',
  icon: 'info-circle',
  defaultProps: {},
  Component: ({ node }) => (
    <div data-field-id={node.id} style={{ color: '#888', fontStyle: 'italic', margin: '8px 0' }}>
      {node.label ?? '说明'}
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>说明内容</label>
      <textarea
        rows={3}
        value={node.label ?? ''}
        style={{ width: '100%', padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }}
        onChange={(e) => onChange({ ...node, label: e.target.value })}
      />
    </div>
  ),
};
