import { request } from '@umijs/max';
import { Alert, App, Input, Modal, Select, Space, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { FormRenderer } from '@/components/FormRenderer/FormRenderer';
import type { FieldMode, SchemaNode } from '@/registry/types';

/** 后端 `CorrectionFormView` 的字段子集，只声明前端真正用到的。 */
type CorrectionFormView = {
  formDataId: number;
  formName: string;
  businessNo?: string | null;
  formDefVersion: number;
  latestFormVersion?: number | null;
  versionDrifted: boolean;
  schemaApproximate: boolean;
  schema: string;
  data: Record<string, unknown>;
  allowedFields: string[];
  blockedFields: Record<string, string>;
  baseRevisionId?: number | null;
  instanceStatus?: string | null;
  reviewRequired: boolean;
};

const REASONS = [
  { value: 'AMOUNT_ERROR', label: '金额/数量录错' },
  { value: 'TYPO', label: '错别字或写错字' },
  { value: 'WRONG_ATTACHMENT', label: '附件传错' },
  { value: 'MISSING_VALUE', label: '内容漏填' },
  { value: 'OTHER', label: '其他' },
];

const BLOCKED_LABELS: Record<string, string> = {
  CONDITION_FIELD: '影响审批走向，不允许事后修改',
  BUSINESS_NUMBER_FIELD: '被业务单号引用，不允许事后修改',
};

type Props = {
  formDataId: number;
  onClose(): void;
  onSaved(): void;
};

/**
 * 修正已提交的表单数据。
 *
 * 两条不能省的约束：
 * 1. 用**单据自己那一版**的 schema 渲染（后端下发），不是表单最新版——否则老单据会缺字段。
 * 2. 只有 `allowedFields` 放开编辑，其余一律只读；判断在后端，这里只是不给用户可点的入口。
 */
export default function CorrectionModal({ formDataId, onClose, onSaved }: Props) {
  const { message } = App.useApp();
  const [view, setView] = useState<CorrectionFormView | null>(null);
  const [schema, setSchema] = useState<SchemaNode[]>([]);
  const [value, setValue] = useState<Record<string, unknown>>({});
  const [reasonCode, setReasonCode] = useState<string>('AMOUNT_ERROR');
  const [reasonText, setReasonText] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    request<CorrectionFormView>(`/api/forms/data/${formDataId}/correction-form`)
      .then((data) => {
        if (!alive) return;
        setView(data);
        try {
          setSchema(JSON.parse(data.schema ?? '[]') as SchemaNode[]);
        } catch {
          setSchema([]);
        }
        setValue((data.data ?? {}) as Record<string, unknown>);
      })
      .catch((error: any) => {
        if (alive) message.error(error?.response?.data?.message ?? '无法打开修正窗口');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [formDataId, message]);

  // 白名单之外一律只读：条件字段、单号字段由后端标记为不可改。
  const fieldModes = useMemo(() => {
    const allowed = new Set(view?.allowedFields ?? []);
    const modes: Record<string, FieldMode> = {};
    for (const node of schema) {
      modes[String(node.id)] = allowed.has(String(node.id)) ? 'runtime-fill' : 'readonly';
    }
    return modes;
  }, [schema, view]);

  const blockedEntries = useMemo(() => Object.entries(view?.blockedFields ?? {}), [view]);

  const submit = useCallback(async () => {
    if (!view) return;
    const changed: Record<string, unknown> = {};
    for (const fieldId of view.allowedFields) {
      if (!Object.is(value[fieldId], view.data?.[fieldId])) {
        changed[fieldId] = value[fieldId];
      }
    }
    if (Object.keys(changed).length === 0) {
      message.warning('没有检测到改动');
      return;
    }
    setSaving(true);
    try {
      const result = await request<{ status: string }>(
        `/api/forms/data/${formDataId}/corrections`,
        {
          method: 'POST',
          data: {
            baseRevisionId: view.baseRevisionId ?? null,
            fields: changed,
            reasonCode,
            reasonText: reasonText || undefined,
          },
        },
      );
      message.success(
        result?.status === 'PENDING_REVIEW'
          ? '已提交复核，复核通过后生效'
          : '已修正',
      );
      onSaved();
      onClose();
    } catch (error: any) {
      // 业务异常在本项目统一返回 422（GlobalExceptionHandler 的约定），所以这里按
      // **错误码**判断而不是 HTTP 状态：拿状态码判 409 永远不成立，会走成通用错误提示。
      const code = error?.response?.data?.code;
      const text = error?.response?.data?.message ?? '修正失败';
      if (code === 'CORRECTION_CONFLICT') {
        // 并发冲突：别人先改了，必须重新打开拿新的基准，不能盲目重试
        message.error(`${text}（请关闭窗口重新打开后再改）`);
      } else {
        message.error(text);
      }
    } finally {
      setSaving(false);
    }
  }, [formDataId, message, onClose, onSaved, reasonCode, reasonText, value, view]);

  return (
    <Modal
      title={`修正数据${view?.businessNo ? ` · ${view.businessNo}` : ''}`}
      open
      width={720}
      confirmLoading={saving}
      okText={view?.reviewRequired ? '提交复核' : '确认修正'}
      cancelText="取消"
      onOk={submit}
      onCancel={onClose}
      okButtonProps={{ disabled: loading || !view }}
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {view?.versionDrifted && (
          <Alert
            type="info"
            showIcon
            message={`这张单据按 v${view.formDefVersion} 表单填报，当前表单已到 v${view.latestFormVersion ?? '?'}`}
            description="修正只影响本单据，不会把它升级到最新表单。"
          />
        )}
        {view?.schemaApproximate && (
          <Alert type="warning" showIcon message="该版本的字段结构为近似还原，请核对字段含义后再修改。" />
        )}
        {view?.reviewRequired && (
          <Alert type="warning" showIcon message="该表单要求双人复核：提交后不会立即生效。" />
        )}
        {blockedEntries.length > 0 && (
          <Alert
            type="info"
            showIcon
            message="部分字段不允许修正"
            description={
              <span>
                {blockedEntries
                  .map(
                    ([fieldId, reason]) =>
                      `${fieldId}：${BLOCKED_LABELS[reason] ?? reason}`,
                  )
                  .join('；')}
              </span>
            }
          />
        )}

        {!loading && schema.length > 0 && (
          <FormRenderer
            schema={schema}
            mode="runtime-fill"
            fieldModes={fieldModes}
            value={value}
            onChange={setValue}
            optionContext={{ dataId: formDataId }}
          />
        )}
        {!loading && schema.length === 0 && (
          <Typography.Text type="secondary">该单据没有可编辑的字段。</Typography.Text>
        )}

        <div>
          <Typography.Text>修正原因</Typography.Text>
          <Select
            style={{ width: '100%', marginTop: 4 }}
            value={reasonCode}
            options={REASONS}
            onChange={setReasonCode}
          />
        </div>
        <div>
          <Typography.Text>补充说明（可选）</Typography.Text>
          <Input.TextArea
            style={{ marginTop: 4 }}
            rows={2}
            maxLength={500}
            value={reasonText}
            onChange={(event) => setReasonText(event.target.value)}
            placeholder="例如：金额应为 3000，原录入 30000"
          />
        </div>
      </Space>
    </Modal>
  );
}
