import { ProTable } from '@ant-design/pro-components';
import { request } from '@umijs/max';

export default function FormDataListPage() {
  return (
    <ProTable
      rowKey="id"
      search={false}
      // 服务端分页：接口返回 { records, total }（本人提交可能很多，之前一次全量返回）。
      request={async (params) => {
        const page = await request<{ records?: any[]; total?: number }>('/api/forms/data', {
          params: { page: params.current, size: params.pageSize },
        });
        return {
          data: page?.records ?? [],
          total: page?.total ?? 0,
          success: true,
        };
      }}
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
