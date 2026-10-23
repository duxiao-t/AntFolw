import { request } from '@umijs/max';
import { Alert, App, Button, Input, Select, Space, Steps, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';
import { collectVisibleValues, firstVisibleValidationError } from '../../../registry/displayConditions';
import type { SchemaNode } from '../../../registry/types';
import { codeMessage, errorCode, type ImportOutcome, type RowIssue } from './importContract';
import { IssueTable, Stat } from './ImportPanels';
import { useCompanyAttribution } from './useCompanyAttribution';

type FormOption = { value: number; label: string };

type Definition = {
  name?: string;
  code?: string;
  version?: number;
  schema?: unknown;
};

/** 422 里的字段级失败：在线填写只有一条记录，`row` 可能不存在。 */
type RawFailure = {
  row?: number;
  fieldId?: string | null;
  fieldLabel?: string | null;
  code?: string;
  message?: string;
};

type SubmitError = {
  code?: string;
  title: string;
  detail?: string;
  failures: RowIssue[];
};

const MANUAL_STEPS = [{ title: '选择表单' }, { title: '填写内容' }, { title: '提交结果' }];

/** schema 在定义接口里是 JSON 字符串，兼容后端已改成数组的情况。 */
function parseSchema(raw: unknown): SchemaNode[] {
  if (Array.isArray(raw)) return raw as SchemaNode[];
  if (typeof raw !== 'string') return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as SchemaNode[]) : [];
  } catch {
    return [];
  }
}

function readSubmitError(error: any): SubmitError {
  const data = error?.response?.data ?? {};
  const raw: RawFailure[] = Array.isArray(data.failures)
    ? data.failures
    : Array.isArray(data.details?.failures) ? data.details.failures : [];
  const failures: RowIssue[] = raw.map((item) => ({
    row: typeof item.row === 'number' ? item.row : 1,
    fieldId: item.fieldId,
    fieldLabel: item.fieldLabel,
    code: item.code ?? 'IMPORT_VALIDATION_FAILED',
    message: item.message ?? '',
  }));
  // 已知业务码用契约里的文案；未知时优先信后端 message，而不是 axios 的 "Request failed ..."。
  const mapped = codeMessage(data.code ?? errorCode(error));
  const serverMessage = typeof data.message === 'string' ? data.message : undefined;
  return {
    code: data.code ?? errorCode(error),
    title: mapped ?? serverMessage ?? error?.message ?? '导入失败',
    detail: mapped && serverMessage && serverMessage !== mapped ? serverMessage : undefined,
    failures,
  };
}

function ActionBar({ children }: { children: React.ReactNode }) {
  return <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>{children}</div>;
}

/**
 * 免文件的「在线填写一条」分支。
 *
 * 由 ImportWizardModal 在选中该方式后才挂载；提交中的状态要回传给父级，
 * 否则用户能在请求飞行途中点遮罩关掉弹窗。
 */
export default function ManualImportStep({
  formOptions,
  defaultFormId,
  onBack,
  onClose,
  onImported,
  onBusyChange,
}: {
  formOptions: FormOption[];
  defaultFormId?: number;
  onBack: () => void;
  onClose: () => void;
  onImported?: () => void;
  onBusyChange?(busy: boolean): void;
}) {
  const { message } = App.useApp();
  const [screen, setScreen] = useState<1 | 2 | 3>(1);
  const [formId, setFormId] = useState<number | undefined>(defaultFormId);
  const [importKey, setImportKey] = useState('');
  // 无部门的全局管理员必须显式选公司；有部门的账号由服务端推导，这里不打扰。
  const { needsCompany, companies, loadFailed: companiesFailed } = useCompanyAttribution();
  const [companyId, setCompanyId] = useState<number | null>(null);
  const [definition, setDefinition] = useState<Definition>();
  const [schema, setSchema] = useState<SchemaNode[]>([]);
  const [data, setData] = useState<Record<string, unknown>>({});
  const [loadingSchema, setLoadingSchema] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [outcome, setOutcome] = useState<ImportOutcome>();
  const [failure, setFailure] = useState<SubmitError>();

  useEffect(() => {
    onBusyChange?.(submitting);
  }, [submitting, onBusyChange]);

  const openForm = async () => {
    if (!formId) return;
    setLoadingSchema(true);
    try {
      const fd = await request<Definition>(`/api/forms/definitions/${formId}`, { skipErrorHandler: true });
      setDefinition(fd);
      setSchema(parseSchema(fd?.schema));
      setData({});
      setOutcome(undefined);
      setFailure(undefined);
      setScreen(2);
    } catch (error: any) {
      message.error(error?.message ?? '加载表单失败，请重试');
    } finally {
      setLoadingSchema(false);
    }
  };

  const submit = async () => {
    if (!formId) return;
    // 与正常填单共用一套校验：必填/格式口径在 registry 里，不在这里另写一份。
    const invalid = firstVisibleValidationError(schema, data);
    if (invalid) {
      message.error(invalid);
      return;
    }
    const body: Record<string, unknown> = {
      formId,
      data: collectVisibleValues(schema, data),
    };
    if (companyId != null) body.companyId = companyId;
    const key = importKey.trim();
    if (key) body.importKey = key;

    setSubmitting(true);
    setFailure(undefined);
    try {
      const result = await request<ImportOutcome>('/api/forms/data/import/manual', {
        method: 'POST',
        data: body,
        skipErrorHandler: true,
      });
      setOutcome(result);
      setScreen(3);
      onImported?.();
    } catch (error: any) {
      setFailure(readSubmitError(error));
      setScreen(3);
    } finally {
      setSubmitting(false);
    }
  };

  const fillAnother = () => {
    // 表单与 schema 保留，只清空这一条的内容与结果。
    setData({});
    setImportKey('');
    setOutcome(undefined);
    setFailure(undefined);
    setScreen(2);
  };

  if (screen === 3 && outcome) {
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Steps size="small" current={2} items={MANUAL_STEPS} />
        <Alert
          type={outcome.failureCount > 0 ? 'warning' : 'success'}
          showIcon
          title={outcome.failureCount > 0 ? '提交完成，有部分失败' : '导入完成'}
          description={`共处理 ${outcome.totalRows} 条${outcome.atomic ? '（原子提交）' : ''}`}
        />
        <Space size={12} wrap>
          <Stat label="成功" value={outcome.successCount} color="var(--af-color-success, #3f8600)" />
          <Stat label="重复跳过" value={outcome.skippedCount} />
          <Stat label="失败" value={outcome.failureCount} color="var(--af-color-danger, #cf1322)" />
        </Space>
        {outcome.warnings.map((warning) => (
          <Alert key={warning} type="warning" showIcon title={warning} />
        ))}
        {outcome.failures.length > 0 && (
          <>
            <Typography.Text strong>失败明细</Typography.Text>
            <IssueTable issues={outcome.failures} />
          </>
        )}
        <ActionBar>
          <Button onClick={onClose}>关闭</Button>
          <Button type="primary" onClick={fillAnother}>再填一条</Button>
        </ActionBar>
      </Space>
    );
  }

  if (screen === 3 && failure) {
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Steps size="small" current={2} items={MANUAL_STEPS} />
        <Alert type="error" showIcon title={failure.title} description={failure.detail} />
        {failure.failures.length > 0 && (
          <>
            <Typography.Text strong>字段明细</Typography.Text>
            <IssueTable issues={failure.failures} />
          </>
        )}
        {failure.code && (
          <Typography.Text type="secondary">{`错误码：${failure.code}`}</Typography.Text>
        )}
        <ActionBar>
          <Button onClick={onClose}>关闭</Button>
          <Button type="primary" onClick={() => { setFailure(undefined); setScreen(2); }}>
            返回修改
          </Button>
        </ActionBar>
      </Space>
    );
  }

  if (screen === 2) {
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Steps size="small" current={1} items={MANUAL_STEPS} />
        <Typography.Text type="secondary">
          {definition?.name ? `正在填写：${definition.name}` : '正在填写'}
          {importKey.trim() ? ` · 外部单号 ${importKey.trim()}` : ''}
        </Typography.Text>
        <FormRenderer
          schema={schema}
          mode="runtime-fill"
          value={data}
          onChange={setData}
          optionContext={{ formCode: definition?.code, formVersion: definition?.version }}
        />
        {schema.length === 0 && (
          <Alert type="warning" showIcon title="该表单没有可填写的字段" />
        )}
        <ActionBar>
          <Button disabled={submitting} onClick={() => setScreen(1)}>上一步</Button>
          <Button disabled={submitting} onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={() => void submit()}>
            提交导入
          </Button>
        </ActionBar>
      </Space>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Steps size="small" current={0} items={MANUAL_STEPS} />
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Typography.Text type="secondary">
          不用准备 Excel，选一张表单直接填一条。导入记录记为已提交，不进入审批流程。
        </Typography.Text>
        <Select
          aria-label="在线填写表单"
          showSearch
          optionFilterProp="label"
          placeholder="选择表单"
          style={{ width: 320 }}
          value={formId}
          onChange={(value: number) => setFormId(value)}
          options={formOptions}
        />
        {needsCompany ? (
          <div style={{ maxWidth: 360 }}>
            <div style={{ marginBottom: 6 }}>归属公司（必选）</div>
            <Select
              aria-label="归属公司"
              showSearch
              optionFilterProp="label"
              placeholder={companiesFailed ? '公司列表加载失败' : '选择归属公司'}
              status={companiesFailed ? 'error' : undefined}
              style={{ width: 320 }}
              value={companyId}
              onChange={(value: number) => setCompanyId(value)}
              options={(companies ?? []).map((c) => ({ value: c.id, label: c.name }))}
            />
            <Typography.Text type="secondary">
              当前账号没有部门，无法自动推断归属，需要手动选一个。
            </Typography.Text>
          </div>
        ) : null}
        <div style={{ maxWidth: 360 }}>
          <div style={{ marginBottom: 6 }}>外部单号（可选）</div>
          <Input
            aria-label="外部单号"
            allowClear
            placeholder="例如 EXT-001"
            value={importKey}
            onChange={(event) => setImportKey(event.target.value)}
          />
          <Typography.Text type="secondary">
            填了可防止重复导入；留空则由系统生成。
          </Typography.Text>
        </div>
      </Space>
      <ActionBar>
        <Button onClick={onBack}>返回</Button>
        <Button onClick={onClose}>取消</Button>
        <Button type="primary" loading={loadingSchema} disabled={!formId}
          onClick={() => void openForm()}>
          下一步
        </Button>
      </ActionBar>
    </Space>
  );
}
