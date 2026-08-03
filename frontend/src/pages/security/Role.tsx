import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Button, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';

export default function RolePage() {
  return (
    <PageContainer>
      <ProTable
        rowKey="id"
        columns={[
          { title: '编码', dataIndex: 'code', key: 'code' },
          { title: '名称', dataIndex: 'name', key: 'name' },
          { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
        ]}
        request={async () => {
          const list = await request('/api/roles');
          return { data: list, success: true, total: list?.length ?? 0 };
        }}
        search={false}
        toolBarRender={() => [
          <Button key="add" type="primary" icon={<PlusOutlined />} onClick={() => message.info('功能开发中')}>新建角色</Button>,
        ]}
      />
    </PageContainer>
  );
}
