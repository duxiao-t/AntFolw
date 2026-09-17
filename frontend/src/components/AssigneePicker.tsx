import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useState } from 'react';

export function AssigneePicker({ mode, value, onChange }: {
  mode: 'user' | 'role';
  value: any;
  onChange: (v: any) => void;
}) {
  const [kw, setKw] = useState('');
  // 选择器语义：只需要 id 与展示名，走最小字段接口；组织/权限域接口对流程配置者不可见。
  const url = mode === 'user'
    ? `/api/mobile/users?keyword=${encodeURIComponent(kw)}`
    : '/api/mobile/roles';
  const { data, isFetching } = useQuery({
    queryKey: ['assignee', mode, kw],
    queryFn: () => request(url).then((r: any) => r ?? []),
  });
  return (
    <Select
      mode="multiple"
      style={{ width: '100%' }}
      value={value}
      loading={isFetching}
      onSearch={setKw}
      onChange={onChange}
      placeholder="搜索并选择"
      filterOption={false}
      notFoundContent={isFetching ? <Spin size="small" /> : null}
      options={(data ?? []).map((x: any) => ({
        value: x.id,
        label: x.displayName ?? x.username ?? x.code ?? `id:${x.id}`,
      }))}
    />
  );
}
