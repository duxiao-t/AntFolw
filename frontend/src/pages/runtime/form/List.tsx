import { ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';

export default function FormDataListPage() {
  const { data } = useQuery({
    queryKey: ['my-form-data'],
    queryFn: () => request<any[]>('/api/forms/data').then((r: any) => r ?? []),
  });
  return (
    <ProTable
      rowKey="id"
      dataSource={data ?? []}
      search={false}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '表单 ID', dataIndex: 'formDefId' },
        { title: '表单版本', dataIndex: 'formDefVersion' },
        { title: '状态', dataIndex: 'status' },
        { title: '提交时间', dataIndex: 'createdAt' },
      ]}
    />
  );
}
