import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useState } from 'react';
import type { FieldType } from '../../registry/types';

export const UserPickerField: FieldType = {
  type: 'user_picker',
  label: '用户选择',
  icon: 'user',
  defaultProps: { required: false, multiple: false, scopeType: 'all' },
  Component: ({ node, mode, value, onChange }) => {
    const [kw, setKw] = useState('');
    // 一次只用一个维度：指定部门（含下级）/ 指定职位 / 部门领导 / 指定人员 / 全部。
    // 后端把这些条件当作相互独立的收窄，所以将来要组合也不必改这里。
    const scopeType = node.props?.scopeType;
    const scopeParams: Record<string, unknown> = {};
    if (scopeType === 'department' && node.props?.scopeDeptId) {
      scopeParams.deptId = node.props.scopeDeptId;
      scopeParams.includeDescendants = true;
    } else if (scopeType === 'position' && node.props?.scopePosition?.trim()) {
      scopeParams.position = node.props.scopePosition.trim();
    } else if (scopeType === 'leader') {
      // 职务口径（含「部长」）留在服务端，前端只说明是哪个预设。
      scopeParams.leaderOnly = true;
    } else if (scopeType === 'user' && node.props?.scopeUserIds?.length) {
      scopeParams.userIds = node.props.scopeUserIds;
    }
    const scopeKey = JSON.stringify(scopeParams);
    const { data, isFetching } = useQuery({
      queryKey: ['users', 'field', kw, scopeKey],
      queryFn: () =>
        request<any[]>('/api/users', { params: { keyword: kw, ...scopeParams } }),
    });
    const multi = !!node.props?.multiple;
    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
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
          placeholder={node.props?.placeholder}
          maxCount={node.props?.maxCount}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
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
