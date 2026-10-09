import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

/** 搜索：普通文本输入，**没有远程查询**（移动端也是）。值就是字符串。 */
export const SearchField: FieldType = {
  type: 'search',
  label: '搜索',
  icon: 'search',
  defaultProps: { required: false, placeholder: '搜索' },
  Component: ({ node, mode, value, onChange }) => {
    const text = typeof value === 'string' ? value : value == null ? '' : String(value);
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>{node.label}{node.props?.required ? ' *' : ''}</div>
        {mode === 'readonly' ? (
          <div style={{ color: 'rgba(0,0,0,0.65)' }}>{text || '未填写'}</div>
        ) : (
          <Input
            type="search"
            allowClear
            disabled={mode !== 'runtime-fill'}
            value={text}
            placeholder={node.props?.placeholder ?? '搜索'}
            onChange={(event) => onChange?.(event.target.value)}
          />
        )}
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <Input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })} />
      <div>占位</div>
      <Input
        value={node.props?.placeholder ?? ''}
        onChange={(e) => onChange({ ...node, props: { ...node.props, placeholder: e.target.value } })}
      />
    </div>
  ),
};
