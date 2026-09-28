import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useEffect, useState } from 'react';

export function AssigneePicker({ mode, value, onChange }: {
  mode: 'user' | 'role';
  value: number[];
  onChange: (v: number[]) => void;
}) {
  const [kw, setKw] = useState('');
  const [keyword, setKeyword] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(kw.trim()), 250);
    return () => clearTimeout(timer);
  }, [kw]);
  // 选择器语义：只需要 id 与展示名，走最小字段接口；组织/权限域接口对流程配置者不可见。
  const url = `/api/pickers/${mode === 'user' ? 'users' : 'roles'}`;
  const selectedIds = (value ?? []).join(',');
  const { data, isFetching } = useQuery({
    queryKey: ['assignee', mode, keyword],
    queryFn: () => request(url, { params: { keyword } }),
  });
  const { data: selected } = useQuery({
    queryKey: ['assignee-selected', mode, selectedIds],
    enabled: !!selectedIds,
    queryFn: () => request(`${url}/selected`, { params: { ids: selectedIds } }),
  });
  const options = [...(selected ?? []), ...(data ?? [])].map((x: any) => ({
    value: x.id as number,
    // 列表端点不再下发登录账号（见 DECISIONS 的 picker-list-narrow-dto），所以姓名后面跟工号
    // 消歧，而不是账号——否则会渲染成"张三 (undefined)"。已选端点仍是完整 DTO，两种形状都有
    // displayName / employeeNo / department。
    label: mode === 'role' ? `${x.name ?? x.code} (${x.code})`
      : `${x.displayName ?? `#${x.id}`}${x.employeeNo ? ` (${x.employeeNo})` : ''}`
        + `${x.department ? ` · ${x.department}` : ''}`,
  }));
  return (
    <Select
      mode="multiple"
      style={{ width: '100%' }}
      value={value ?? []}
      loading={isFetching}
      onSearch={setKw}
      onChange={onChange}
      maxTagCount={3}
      placeholder={mode === 'user' ? '搜索姓名或部门' : '搜索角色名称或编码'}
      filterOption={false}
      notFoundContent={isFetching ? <Spin size="small" /> : '没有匹配项'}
      options={Array.from(new Map(options.map((option) => [option.value, option])).values())}
    />
  );
}
