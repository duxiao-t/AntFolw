import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  List,
  Space,
  Tag,
  theme,
} from 'antd';
import type { SchemaNode } from '../../registry/types';
import MobileFormPreview from './MobileFormPreview';
import type { PublishCheckItem, PublishCheckStatus } from './wizardTypes';

type Props = {
  formId: number | null;
  definition?: AntFlowAPI.FormDefinition;
  workflowEnabled: boolean;
  processStatus?: string;
  previewSchema: SchemaNode[];
  publishChecks: PublishCheckItem[];
  publishErrors: PublishCheckItem[];
  visibilitySummary: string;
  maintainerCount: number;
  savingDraft: boolean;
  publishing: boolean;
  onBack: () => void;
  onSaveDraft: () => void;
  onPublish: () => void;
};

export default function FormPublishStep({
  formId,
  definition,
  workflowEnabled,
  processStatus,
  previewSchema,
  publishChecks,
  publishErrors,
  visibilitySummary,
  maintainerCount,
  savingDraft,
  publishing,
  onBack,
  onSaveDraft,
  onPublish,
}: Props) {
  const { token } = theme.useToken();
  const checkIcon = (status: PublishCheckStatus) => {
    if (status === 'success') {
      return <CheckCircleOutlined style={{ color: token.colorSuccess }} />;
    }
    if (status === 'warning') {
      return (
        <ExclamationCircleOutlined style={{ color: token.colorWarning }} />
      );
    }
    return <CloseCircleOutlined style={{ color: token.colorError }} />;
  };

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'minmax(420px, 1fr) 360px',
        gap: 24,
        alignItems: 'start',
      }}
    >
      <Card title="手机端预览">
        <MobileFormPreview
          formId={formId ?? undefined}
          title={definition?.name ?? '未命名表单'}
          description={definition?.description}
          schema={previewSchema}
        />
      </Card>

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card title="发布检查">
          <List
            dataSource={publishChecks}
            renderItem={(item) => (
              <List.Item>
                <List.Item.Meta
                  avatar={checkIcon(item.status)}
                  title={item.title}
                  description={item.description}
                />
              </List.Item>
            )}
          />
        </Card>

        <Card title="发布设置">
          <Descriptions column={1} size="small">
            <Descriptions.Item label="表单状态">
              {definition?.status ?? '-'}
            </Descriptions.Item>
            <Descriptions.Item label="审批流程">
              {workflowEnabled ? (
                <Tag color="blue">启用</Tag>
              ) : (
                <Tag>未启用</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="提交后行为">
              {workflowEnabled ? '提交后进入审批' : '提交成功后直接完成'}
            </Descriptions.Item>
            <Descriptions.Item label="使用范围">{visibilitySummary}</Descriptions.Item>
            <Descriptions.Item label="维护人员">
              {maintainerCount} 人
            </Descriptions.Item>
            {workflowEnabled && (
              <Descriptions.Item label="流程状态">
                {processStatus ?? '未保存'}
              </Descriptions.Item>
            )}
          </Descriptions>
        </Card>

        <Card>
          {publishErrors.length > 0 && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 12 }}
              title="发布检查未通过"
              description={publishErrors[0].description}
            />
          )}
          <Space wrap>
            <Button onClick={onBack}>
              上一步
            </Button>
            <Button loading={savingDraft} onClick={onSaveDraft}>
              保存草稿
            </Button>
            <Button
              type="primary"
              disabled={publishErrors.length > 0}
              loading={publishing}
              onClick={onPublish}
            >
              发布
            </Button>
          </Space>
        </Card>
      </Space>
    </div>
  );
}
