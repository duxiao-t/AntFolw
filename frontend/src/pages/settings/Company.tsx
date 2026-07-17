import { PageContainer, ProForm, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { message, Card, Upload, Button } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import { useEffect } from 'react';

export default function CompanySettingsPage() {
  useEffect(() => {
    (async () => {
      try {
        const list = await request('/api/companies');
        if (list?.length) {
          // store first company info locally for display
          setLogo(undefined);
        }
      } catch { /* 后端无数据时忽略 */ }
    })();
  }, []);

  return (
    <PageContainer>
      <Card title="企业基础信息">
        <ProForm
          onFinish={async () => { message.info('保存成功（演示）'); }}
          submitter={{ render: (_, dom) => [...dom.reverse()] }}
        >
          <ProFormText name="name" label="企业名称" placeholder="请输入企业名称" />
          <ProFormTextArea name="address" label="企业地址" placeholder="请输入地址" />
          <ProForm.Item label="企业 LOGO">
            <Upload listType="picture" maxCount={1}>
              <Button icon={<UploadOutlined />}>上传 LOGO</Button>
            </Upload>
          </ProForm.Item>
          <ProFormText name="footer" label="页脚文案" placeholder="版权所有 © 2026" />
        </ProForm>
      </Card>
    </PageContainer>
  );
}
