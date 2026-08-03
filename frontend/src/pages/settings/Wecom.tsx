import { PageContainer, ProForm, ProFormText } from '@ant-design/pro-components';
import { message } from 'antd';

export default function WecomPage() {
  return (
    <PageContainer>
      <ProForm
        onFinish={async () => { message.info('保存成功（演示）'); }}
        submitter={{ render: (_, dom) => [...dom.reverse()] }}
      >
        <ProFormText name="corpId" label="企业 ID (CorpID)" placeholder="ww..." />
        <ProFormText name="agentId" label="应用 AgentId" placeholder="1000001" />
        <ProFormText name="secret" label="应用 Secret" placeholder="••••••••" />
      </ProForm>
    </PageContainer>
  );
}
