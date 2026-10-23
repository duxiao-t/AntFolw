import { DownloadOutlined, FileExcelOutlined, FormOutlined } from '@ant-design/icons';
import { request } from '@umijs/max';
import { Alert, App, Button, Checkbox, Modal, Select, Space, Steps, Table, Tag, Typography } from 'antd';
import type { CSSProperties, DragEvent, ReactNode } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { blobErrorMessage } from '@/utils/format';
import {
  cellText, errorCode, errorText, saveBlob, sheetsFromError,
  type ColumnReport, type ImportInspection, type ImportOutcome,
} from './importContract';
import { downloadImportFailures } from './importFailures';
import { IssueTable, Stat } from './ImportPanels';
import ManualImportStep from './ManualImportStep';
import { useCompanyAttribution } from './useCompanyAttribution';

const DROP: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: '28px 16px',
  border: '1px dashed var(--af-color-line)', borderRadius: 8, background: 'var(--af-color-surface)',
};
const STEP_ITEMS = [{ title: '选择表单' }, { title: '上传文件' }, { title: '预检结果' }, { title: '确认导入' }];

/** 两种导入方式的入口卡：整块可点，选中的那张高亮。 */
function MethodCard({ icon, title, description, selected, onClick }: {
  icon: ReactNode;
  title: string;
  description: string;
  selected: boolean;
  onClick(): void;
}) {
  const style: CSSProperties = {
    display: 'flex', gap: 12, padding: '14px 16px', textAlign: 'left', cursor: 'pointer',
    borderRadius: 8, background: selected ? 'var(--af-color-primary-bg, #e6f4ff)' : 'var(--af-color-surface)',
    border: `1px solid ${selected ? 'var(--af-color-primary, #1677ff)' : 'var(--af-color-line)'}`,
  };
  return (
    <button type="button" aria-pressed={selected} onClick={onClick} style={style}>
      <span style={{ fontSize: 20, lineHeight: 1.2 }}>{icon}</span>
      <span>
        <span style={{ display: 'block', fontWeight: 600 }}>{title}</span>
        <span style={{ display: 'block', fontSize: 12, color: 'var(--af-color-muted)' }}>{description}</span>
      </span>
    </button>
  );
}

/**
 * 表单数据导入向导：选表单 → 传文件 → 看预检 → 确认导入。
 *
 * 由调用方「开着才渲染」（与 ExportModal 一致），所以关闭即卸载、状态自然全清，
 * 不需要在关闭时逐项 reset——这比手动重置更难写漏。
 */
export default function ImportWizardModal({ defaultFormId, onClose, onImported }: {
  defaultFormId?: number;
  onClose: () => void;
  onImported?: () => void;
}) {
  const { message } = App.useApp();
  const [step, setStep] = useState(0);
  const [formId, setFormId] = useState<number | undefined>(defaultFormId);
  const [formOptions, setFormOptions] = useState<Array<{ value: number; label: string }>>([]);
  const [templateBusy, setTemplateBusy] = useState(false);
  const [file, setFile] = useState<File>();
  const [sheets, setSheets] = useState<string[]>([]);
  const [sheet, setSheet] = useState('');
  const [inspection, setInspection] = useState<ImportInspection>();
  const [inspecting, setInspecting] = useState(false);
  const [skipErrorRows, setSkipErrorRows] = useState(false);
  // 无部门的全局管理员必须显式选公司，否则后端以 BAD_COMPANY 拒绝（导入/发起同一套规则）。
  const { needsCompany, companies, loadFailed: companiesFailed } = useCompanyAttribution();
  const [companyId, setCompanyId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [outcome, setOutcome] = useState<ImportOutcome>();
  // 在线填写是独立分支：自带屏幕与底部操作条，父级只负责「提交中不许关」。
  const [manual, setManual] = useState(false);
  const [manualBusy, setManualBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  // 预检序号：换文件/换工作表后旧响应必须作废，否则会"看着 A 的结果、导的是 B"。
  const inspectSeq = useRef(0);

  useEffect(() => {
    request<{ records?: Array<{ id: number; name: string }> }>('/api/forms/definitions', {
      params: { page: 1, size: 100 },
      skipErrorHandler: true,
    })
      .then((page) => setFormOptions((page.records ?? []).map((form) => ({ value: form.id, label: form.name }))))
      .catch(() => setFormOptions([]));
  }, []);

  const invalidateInspection = () => {
    inspectSeq.current += 1;
    setInspection(undefined);
    setInspecting(false);
  };

  const changeForm = (value: number) => {
    setFormId(value);
    // 表单换了，之前的文件与预检都不再成立（字段集可能完全不同）。
    setFile(undefined);
    setSheet('');
    setSheets([]);
    setOutcome(undefined);
    setSkipErrorRows(false);
    invalidateInspection();
  };

  const inspectFile = async (target: File, sheetName?: string) => {
    const seq = ++inspectSeq.current;
    setInspection(undefined);
    setInspecting(true);
    try {
      const data = new FormData();
      data.append('formId', String(formId));
      data.append('file', target);
      if (sheetName) data.append('sheetName', sheetName);
      if (companyId != null) data.append('companyId', String(companyId));
      const result = await request<ImportInspection>('/api/forms/data/import/inspect', {
        method: 'POST',
        data,
        skipErrorHandler: true,
      });
      if (seq !== inspectSeq.current) return;
      setInspection(result);
      setSheets(result.sheets ?? []);
      if (result.sheetName) setSheet(result.sheetName);
    } catch (error: any) {
      if (seq !== inspectSeq.current) return;
      const available = sheetsFromError(error);
      if (available.length) {
        setSheets(available);
        setSheet('');
      }
      message.error(errorText(error, '预检失败'));
    } finally {
      if (seq === inspectSeq.current) setInspecting(false);
    }
  };

  const pickFile = (picked?: File) => {
    if (!picked) return;
    // 前端先拦一次：选到 .xls/.csv 时没必要等一轮请求才知道不支持。
    if (!/\.xlsx$/i.test(picked.name)) {
      message.error('只支持 .xlsx 文件，请按模板另存为 .xlsx 后重试');
      return;
    }
    setFile(picked);
    setSheet('');
    setSheets([]);
    setOutcome(undefined);
    setSkipErrorRows(false);
    void inspectFile(picked);
  };

  const changeSheet = (value: string) => {
    setSheet(value);
    if (file) void inspectFile(file, value);
  };

  const clearFile = () => {
    setFile(undefined);
    setSheet('');
    setSheets([]);
    setOutcome(undefined);
    invalidateInspection();
  };

  const downloadTemplate = async () => {
    if (!formId) return;
    setTemplateBusy(true);
    try {
      const blob = await request('/api/forms/data/import/template', {
        params: { formId },
        responseType: 'blob',
        skipErrorHandler: true,
      });
      saveBlob(blob as Blob, `表单导入模板-${formId}.xlsx`);
      message.success('模板已开始下载');
    } catch (error) {
      // 下载接口出错时后端返回 JSON，但 responseType 是 blob：先按业务码判，再读 blob 里的 message。
      message.error(errorCode(error) ? errorText(error, '模板下载失败') : await blobErrorMessage(error));
    } finally {
      setTemplateBusy(false);
    }
  };

  const submit = async () => {
    if (!file || !inspection || !formId) return;
    setSubmitting(true);
    try {
      const data = new FormData();
      data.append('formId', String(formId));
      data.append('file', file);
      if (sheet) data.append('sheetName', sheet);
      data.append('skipErrorRows', skipErrorRows ? 'true' : 'false');
      if (companyId != null) data.append('companyId', String(companyId));
      const result = await request<ImportOutcome>('/api/forms/data/import', {
        method: 'POST',
        data,
        skipErrorHandler: true,
      });
      setOutcome(result);
      onImported?.();
    } catch (error: any) {
      message.error(errorText(error, '导入失败'));
    } finally {
      setSubmitting(false);
    }
  };

  // 列对不上就不让继续：带着缺列导进去只会得到一批半成品记录。
  const blocking = useMemo(() => {
    if (!inspection) return [];
    const reasons: string[] = [];
    if (inspection.missingColumns.length) {
      reasons.push(`模板中缺少这些列：${inspection.missingColumns.join('、')}（必填列缺失会导致逐行校验失败）`);
    }
    const unsupported = inspection.columns
      .filter((column) => !column.supported)
      .map((column) => column.label || column.fieldId);
    if (unsupported.length) reasons.push(`文件里有表单不支持的列：${unsupported.join('、')}`);
    const unknown = inspection.issues.filter((issue) => issue.code === 'IMPORT_FIELD_UNKNOWN').length;
    if (unknown) reasons.push(`存在未知字段 ${unknown} 处`);
    return reasons;
  }, [inspection]);

  const previewRows = useMemo(() => (inspection?.previews ?? []).slice(0, 20), [inspection]);
  const previewColumns = useMemo(() => {
    const seen = new Set<string>();
    previewRows.forEach((row) => Object.keys(row).forEach((key) => seen.add(key)));
    return [...seen];
  }, [previewRows]);

  const canNext = step === 0
    ? Boolean(formId)
    : step === 1
      ? Boolean(inspection) && !inspecting
      : step === 2
        ? Boolean(inspection) && blocking.length === 0
        : inspection != null && (inspection.errorCount === 0 || skipErrorRows) && inspection.importableCount > 0;

  const busy = submitting || manualBusy;
  const footer = manual ? null : outcome ? (
    <Button type="primary" onClick={onClose}>关闭</Button>
  ) : (
    <>
      <Button disabled={submitting} onClick={onClose}>取消</Button>
      {step > 0 && <Button disabled={submitting} onClick={() => setStep(step - 1)}>上一步</Button>}
      {step < 3 ? (
        <Button type="primary" loading={step === 1 && inspecting} disabled={!canNext || submitting}
          onClick={() => setStep(step + 1)}>
          下一步
        </Button>
      ) : (
        <Button type="primary" loading={submitting} disabled={!canNext} onClick={() => void submit()}>
          开始导入
        </Button>
      )}
    </>
  );

  return (
    <Modal
      title="导入数据"
      open
      onCancel={() => { if (!busy) onClose(); }}
      mask={{ closable: !busy }}
      keyboard={!busy}
      width={880}
      footer={footer}
    >
      {manual ? (
        <ManualImportStep
          formOptions={formOptions}
          defaultFormId={formId}
          onBack={() => setManual(false)}
          onClose={onClose}
          onImported={onImported}
          onBusyChange={setManualBusy}
        />
      ) : outcome ? (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Alert type={outcome.failureCount > 0 ? 'warning' : 'success'} showIcon
            title={outcome.failureCount > 0 ? '导入完成，有部分行失败' : '导入完成'}
            description={`共处理 ${outcome.totalRows} 行${outcome.atomic ? '（整批原子提交）' : ''}`} />
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
          {outcome.failuresTruncated && (
            <Typography.Text type="warning">仅显示前 200 条错误记录；修正后重新预检即可看到后续问题。</Typography.Text>
          )}
          <Button icon={<DownloadOutlined />} disabled={outcome.failures.length === 0}
            onClick={() => downloadImportFailures(outcome.failures, `导入失败明细-${outcome.batchId}.csv`)}>
            下载失败明细
          </Button>
        </Space>
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <Steps size="small" current={step} items={STEP_ITEMS} />

          {step === 0 && (
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              <Typography.Text strong>选择导入方式</Typography.Text>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 12 }}>
                <MethodCard
                  icon={<FormOutlined />}
                  title="在线填写一条"
                  description="不用准备文件，直接在页面里填一条"
                  selected={false}
                  onClick={() => setManual(true)}
                />
                <MethodCard
                  icon={<FileExcelOutlined />}
                  title="上传 Excel（可批量）"
                  description="先下载模板，可一次导入多行"
                  selected
                  onClick={() => setManual(false)}
                />
              </div>
              <Typography.Text type="secondary">
                先选表单，再下载该表单的导入模板；模板带字段列与填写说明，请勿改动表头。
              </Typography.Text>
              <Space wrap>
                <Select aria-label="表单" showSearch optionFilterProp="label" placeholder="选择表单"
                  style={{ width: 320 }} value={formId} onChange={changeForm} options={formOptions} />
                {needsCompany ? (
                  <Select aria-label="归属公司" showSearch optionFilterProp="label"
                    placeholder={companiesFailed ? '公司列表加载失败' : '选择归属公司（必选）'}
                    status={companiesFailed ? 'error' : undefined}
                    style={{ width: 240 }} value={companyId} onChange={setCompanyId}
                    options={(companies ?? []).map((c) => ({ value: c.id, label: c.name }))} />
                ) : null}
                <Button icon={<DownloadOutlined />} loading={templateBusy} disabled={!formId}
                  onClick={() => void downloadTemplate()}>
                  下载导入模板
                </Button>
              </Space>
              <Alert type="info" showIcon title="导入记录直接记为已提交"
                description="导入不进入审批流程，也不会通知审批人。" />
            </Space>
          )}
          {step === 1 && (
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              <div style={DROP}
                onDragOver={(event: DragEvent) => event.preventDefault()}
                onDrop={(event: DragEvent) => { event.preventDefault(); pickFile(event.dataTransfer.files?.[0]); }}>
                <FileExcelOutlined />
                <Typography.Text>把 .xlsx 文件拖到这里，或</Typography.Text>
                <Button onClick={() => fileInput.current?.click()}>选择文件</Button>
              </div>
              <input ref={fileInput} type="file" accept=".xlsx" aria-label="选择导入文件"
                style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }}
                onChange={(event) => {
                  const picked = event.target.files?.[0];
                  event.target.value = '';
                  pickFile(picked);
                }} />
              {file && (
                <Space wrap>
                  <Typography.Text>已选择：{file.name}</Typography.Text>
                  <Button size="small" onClick={clearFile}>清除</Button>
                </Space>
              )}
              {inspecting && <Typography.Text type="secondary">正在预检…</Typography.Text>}
              {sheets.length > 1 && (
                <Space wrap>
                  <Typography.Text>工作表</Typography.Text>
                  <Select aria-label="工作表" style={{ width: 240 }} placeholder="选择工作表"
                    value={sheet || undefined} onChange={changeSheet}
                    options={sheets.map((value) => ({ value, label: value }))} />
                </Space>
              )}
              {!file && <Typography.Text type="secondary">只支持 .xlsx；选完文件会自动预检。</Typography.Text>}
            </Space>
          )}

          {step === 2 && inspection && (
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              <Space size={12} wrap>
                <Stat label="可导入" value={inspection.importableCount} color="var(--af-color-success, #3f8600)" />
                <Stat label="有错误" value={inspection.errorCount} color="var(--af-color-danger, #cf1322)" />
                <Stat label="将跳过" value={inspection.skippedCount} />
              </Space>
              <Typography.Text type="secondary">
                {`${inspection.formName}（${inspection.formCode}）· 模板 ${inspection.templateVersion} · 共 ${inspection.rowCount} 行`}
              </Typography.Text>
              {inspection.warnings.map((warning) => (
                <Alert key={warning} type="warning" showIcon title={warning} />
              ))}
              {blocking.map((reason) => (
                <Alert key={reason} type="error" showIcon title={reason}
                  description="请修改文件后重新上传，这一步不能继续。" />
              ))}
              <Typography.Text strong>列对账</Typography.Text>
              <Table<ColumnReport>
                size="small" pagination={false} rowKey="fieldId"
                scroll={{ x: 'max-content', y: 200 }} dataSource={inspection.columns}
                columns={[
                  { title: '字段', dataIndex: 'label', ellipsis: true },
                  { title: '类型', dataIndex: 'type', width: 120 },
                  { title: '必填', dataIndex: 'required', width: 70, render: (value: boolean) => (value ? '是' : '否') },
                  {
                    title: '是否匹配', dataIndex: 'supported', width: 100,
                    render: (value: boolean) => <Tag color={value ? 'green' : 'red'}>{value ? '匹配' : '不支持'}</Tag>,
                  },
                ]}
              />
              <Typography.Text strong>逐行错误</Typography.Text>
              <IssueTable issues={inspection.issues} />
              {inspection.issuesTruncated && (
                <Typography.Text type="warning">仅显示前 200 条，其余错误在导入后下载失败明细查看。</Typography.Text>
              )}
              <Typography.Text strong>{`原始值预览（前 ${previewRows.length} 行）`}</Typography.Text>
              <Table size="small" pagination={false} scroll={{ x: 'max-content', y: 220 }}
                rowKey="key" dataSource={previewRows.map((row, key) => ({ key, row }))}
                columns={previewColumns.map((column) => ({
                  title: column,
                  ellipsis: true,
                  render: (_: unknown, item: { row: Record<string, unknown> }) => cellText(item.row[column]),
                }))} />
            </Space>
          )}

          {step === 3 && inspection && (
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              <Alert type="info" showIcon
                title="导入记录记为已提交，不进入审批流程"
                description="导入的数据会直接出现在「提交数据」里；需要走审批的记录请由用户正常填写提交。" />
              <Space size={12} wrap>
                <Stat label="可导入" value={inspection.importableCount} />
                <Stat label="有错误" value={inspection.errorCount} />
                <Stat label="将跳过" value={inspection.skippedCount} />
              </Space>
              <Space direction="vertical" size={4}>
                <Checkbox checked={skipErrorRows} disabled={inspection.errorCount === 0}
                  onChange={(event) => setSkipErrorRows(event.target.checked)}>
                  跳过错误行继续
                </Checkbox>
                <Typography.Text>
                  {skipErrorRows && inspection.errorCount > 0
                    ? `将导入 ${inspection.importableCount} 行，丢弃 ${inspection.errorCount} 行`
                    : `将导入 ${inspection.importableCount} 行`}
                </Typography.Text>
                {inspection.errorCount > 0 && !skipErrorRows && (
                  <Typography.Text type="warning">
                    {`有 ${inspection.errorCount} 行错误；不勾选「跳过错误行」时无法开始导入。`}
                  </Typography.Text>
                )}
              </Space>
              <Typography.Text type="secondary">
                {`文件 ${file?.name ?? ''}${sheet ? ` · 工作表 ${sheet}` : ''}`}
              </Typography.Text>
            </Space>
          )}
        </Space>
      )}
    </Modal>
  );
}
