import { PageContainer, ProForm, ProFormText } from '@ant-design/pro-components';
import { message } from 'antd';

export default function S3StoragePage() {
  return (
    <PageContainer>
      <ProForm
        onFinish={async () => { message.info('保存成功（演示）'); }}
        submitter={{ render: (_, dom) => [...dom.reverse()] }}
      >
        <ProFormText name="endpoint" label="S3 Endpoint" placeholder="https://s3.amazonaws.com" />
        <ProFormText name="bucket" label="Bucket 名称" placeholder="antflow-uploads" />
        <ProFormText name="accessKey" label="Access Key" placeholder="AKIA..." />
        <ProFormText name="secretKey" label="Secret Key" placeholder="••••••••" />
      </ProForm>
    </PageContainer>
  );
}
