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
        request={async () => {
          const list = await request('/api/forms/definitions');
          return { data: list, success: true, total: list?.length ?? 0 };
        }}
        search={false}
        onRow={(record: any) => ({
          onClick: () => history.push(`/designer/form/${record.id}`),
          style: { cursor: 'pointer' },
        })}
        toolBarRender={() => [
          <Button key="add" type="primary" icon={<PlusOutlined />}
            onClick={() => history.push('/designer/form/new')}>新建模板</Button>,
        ]}
      />
    </PageContainer>
  );
}
