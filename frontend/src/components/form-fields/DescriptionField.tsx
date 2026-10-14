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
};
