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
  const url = mode === 'user' ? `/api/users?keyword=${kw}` : `/api/roles`;
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
