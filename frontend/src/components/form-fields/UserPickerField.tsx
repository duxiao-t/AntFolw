import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useState } from 'react';
import type { FieldType } from '../../registry/types';

export const UserPickerField: FieldType = {
  type: 'user_picker',
  label: '用户选择',
  icon: 'user',
  defaultProps: { required: false, multiple: false },
  Component: ({ node, mode, value, onChange }) => {
    const [kw, setKw] = useState('');
    const { data, isFetching } = useQuery({
      queryKey: ['users', 'field', kw],
      queryFn: () => request<any[]>('/api/users', { params: { keyword: kw } }),
    });
    const multi = !!node.props?.multiple;
    return (
      <div data-field-id={node.id}>
        <label style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </label>
        <Select
          mode={multi ? 'multiple' : undefined}
          showSearch
          disabled={mode !== 'runtime-fill'}
          value={value}
          loading={isFetching}
          onSearch={setKw}
          onChange={(v) => onChange?.(v)}
          filterOption={false}
          options={(data ?? []).map((u: any) => ({ value: u.id, label: u.displayName ?? u.username }))}
          notFoundContent={isFetching ? <Spin size="small" /> : '无匹配用户'}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>
        <input type="checkbox" checked={!!node.props?.multiple}
          onChange={(e) => onChange({ ...node, props: { ...node.props, multiple: e.target.checked } })} />
        {' '}允许多选
      </label>
    </div>
  ),
};
