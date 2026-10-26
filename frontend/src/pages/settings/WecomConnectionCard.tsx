import {
  CheckCircleFilled,
  KeyOutlined,
  SafetyCertificateOutlined,
  WechatOutlined,
} from '@ant-design/icons';
import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Switch,
  TimePicker,
  Typography,
} from 'antd';
import type { FormInstance } from 'antd';
import type { FormValues, Settings } from './wecomModel';
import { WECOM_GREEN } from './wecomModel';
import { useWecomStyles } from './wecomStyles';

type Props = {
  form: FormInstance<FormValues>;
  settings?: Settings;
  /** 卡片骨架屏：公司列表或设置任一在加载。 */
  loading: boolean;
  dirty: boolean;
  saving: boolean;
  onDirty: () => void;
  onSave: (values: FormValues) => void;
};

export function WecomConnectionCard({
  form,
  settings,
  loading,
  dirty,
  saving,
  onDirty,
  onSave,
}: Props) {
  const { styles } = useWecomStyles();
  const scheduleEnabled = Form.useWatch('scheduleEnabled', form);

  return (
    <Card
      className={styles.card}
      loading={loading}
      title={(
        <span className={styles.cardTitle}>
          <span className={styles.icon}><WechatOutlined /></span>
          连接配置
        </span>
      )}
    >
      <Typography.Text className={styles.intro}>
        使用企业微信管理后台的“通讯录同步 Secret”。凭证加密保存且不会再次显示。
      </Typography.Text>
      <Form<FormValues>
        className={styles.form}
        form={form}
        layout="vertical"
        requiredMark="optional"
        onValuesChange={() => onDirty()}
        onFinish={(values) => onSave(values)}
      >
        <Form.Item
          name="corpId"
          label="企业 ID（CorpID）"
          rules={[{ required: true, whitespace: true, message: '请输入 CorpID' }]}
        >
          <Input prefix={<SafetyCertificateOutlined />} maxLength={128} placeholder="ww..." />
        </Form.Item>
        <Form.Item name="agentId" label="自建应用 AgentId">
          <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="企业微信应用 AgentId" />
        </Form.Item>
        <Form.Item
          name="agentSecret"
          label="自建应用 Secret"
          extra={settings?.agentSecretConfigured
            ? <span className={styles.secretHint}><CheckCircleFilled style={{ color: WECOM_GREEN }} /> 已安全配置，留空表示不修改</span>
            : '与通讯录同步 Secret 分开配置，保存后不会回显'}
        >
          <Input.Password prefix={<KeyOutlined />} maxLength={512} autoComplete="new-password"
            placeholder={settings?.agentSecretConfigured ? '已配置（留空不修改）' : '请输入应用 Secret'} />
        </Form.Item>
        <Form.Item name="oauthEnabled" label="企业微信登录" valuePropName="checked"
          extra="启用后在移动端登录页显示企业微信登录按钮，不会自动跳转授权">
          <Switch />
        </Form.Item>
        <Form.Item name="jsSdkEnabled" label="表单 JS-SDK 能力" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item name="messageEnabled" label="审批应用消息" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item
          name="secret"
          label="通讯录同步 Secret"
          extra={settings?.secretConfigured
            ? <span className={styles.secretHint}><CheckCircleFilled style={{ color: WECOM_GREEN }} /> 已安全配置，留空保存表示不修改</span>
            : '首次连接必须填写，保存后不会回显'}
        >
          <Input.Password
            prefix={<KeyOutlined />}
            maxLength={512}
            autoComplete="new-password"
            placeholder={settings?.secretConfigured ? '已配置（留空不修改）' : '请输入通讯录同步 Secret'}
          />
        </Form.Item>
        <Form.Item
          name="scheduleEnabled"
          label="每日自动同步"
          valuePropName="checked"
          extra="到达设定时间后自动同步通讯录；服务错过时间会在当天补跑"
        >
          <Switch aria-label="每日自动同步" />
        </Form.Item>
        <Form.Item
          name="scheduleTime"
          label="执行时间"
          rules={[{ required: true, message: '请选择执行时间' }]}
        >
          <TimePicker
            aria-label="自动同步执行时间"
            allowClear={false}
            disabled={!scheduleEnabled}
            format="HH:mm"
            style={{ width: '100%' }}
          />
        </Form.Item>
        <Form.Item name="scheduleMode" label="自动同步模式">
          <Radio.Group aria-label="自动同步模式" disabled={!scheduleEnabled}>
            <Radio.Button value="INCREMENTAL">增量同步</Radio.Button>
            <Radio.Button value="FULL">全量同步</Radio.Button>
          </Radio.Group>
        </Form.Item>
        <Space wrap>
          <Button
            type="primary"
            htmlType="submit"
            loading={saving}
          >
            保存连接配置
          </Button>
          {dirty && <Typography.Text type="warning">配置有未保存修改</Typography.Text>}
        </Space>
      </Form>
    </Card>
  );
}
