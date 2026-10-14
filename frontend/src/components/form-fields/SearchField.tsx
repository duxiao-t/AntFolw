import { Space } from 'antd';
import { PanelField } from './panelField';
import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

/** 搜索：普通文本输入，**没有远程查询**（移动端也是）。值就是字符串。 */
export const SearchField: FieldType = {
  type: 'search',
  label: '搜索',
  icon: 'search',
  defaultProps: { required: false, placeholder: '搜索' },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <PanelField label="占位提示">
          <Input
            value={props.placeholder ?? ''}
            onChange={(event) => updateProps({ placeholder: event.target.value })}
          />
        </PanelField>
      </Space>
    );
  },
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
};
