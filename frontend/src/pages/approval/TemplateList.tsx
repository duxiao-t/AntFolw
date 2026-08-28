import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Button } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { request, history } from '@umijs/max';

export default function TemplateListPage() {
  return (
    <PageContainer>
      <ProTable
        rowKey="id"
        columns={[
          { title: '编码', dataIndex: 'code', key: 'code' },
          { title: '名称', dataIndex: 'name', key: 'name' },
          { title: '状态', dataIndex: 'status', key: 'status', valueEnum: { DRAFT: '草稿', PUBLISHED: '已发布', DEPRECATED: '已弃用' } },
          { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
        ]}
        request={async (params) => {
          const result = await request<{ records?: unknown[]; total?: number }>(
            '/api/forms/definitions',
            { params: { page: params.current, size: params.pageSize } },
          );
          return { data: result.records ?? [], success: true, total: result.total ?? 0 };
        }}
        search={false}
        onRow={(record: any) => ({
          onClick: () => history.push(`/approval/forms/${record.id}/wizard?step=designer`),
          style: { cursor: 'pointer' },
        })}
        toolBarRender={() => [
          <Button key="add" type="primary" icon={<PlusOutlined />}
            onClick={() => history.push('/approval/forms/new')}>新建模板</Button>,
        ]}
      />
    </PageContainer>
  );
}
