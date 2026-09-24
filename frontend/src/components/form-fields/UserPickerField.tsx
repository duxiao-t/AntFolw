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
    // 始终带上范围类型：空名单会被序列化丢掉，只有 scopeType 能让服务端分清"没配范围"和
    // "配了指定人员但名单是空的"（后者应当零候选，不能退化成全员）。
    const scopeParams: Record<string, unknown> = { scopeType };
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
        // 走选择器专用端点（只要求"已进管理端"）：/api/users 还要 org:user:read，
        // 于是没有该能力的普通提交人在运行时填表单时拉不到任何候选。
        request<any[]>('/api/pickers/users', { params: { keyword: kw, ...scopeParams } }),
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
          // 列表端点不再下发登录账号（那是唯一能被关键字枚举的入口），所以没有 username 可退。
          options={(data ?? []).map((u: any) => ({ value: u.id, label: u.displayName ?? `#${u.id}` }))}
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
