import { FileExcelOutlined } from '@ant-design/icons';
import { App, Alert, Button, Input, Modal, Select, Space, Table, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { request } from '@umijs/max';

type Preview = {
  sheets: string[];
  columns: string[];
  rowCount: number;
  rows: Array<Record<string, string>>;
};

/**
 * 导入新版本：粘贴文本或上传表格 → 预检 → 存成**待发布**版本。
 *
 * 这里所有竞态处理都围着一条不变的规矩：**预览必须永远对应当前输入**。
 * 输入变了、工作表换了、文件清了，就立刻作废在途的预检并清掉预览——
 * 否则会出现"看着 A 的内容、导入的是 B"（导入是覆盖草稿，代价很大）。
 */
export function OptionSourceImportModal({ sourceId, open, onClose, onImported }: {
  sourceId: number;
  open: boolean;
  onClose(): void;
  onImported(): void;
}) {
  const { message } = App.useApp();
  const [text, setText] = useState('');
  const [file, setFile] = useState<File>();
  const [sheet, setSheet] = useState('');
  const [note, setNote] = useState('');
  const [preview, setPreview] = useState<Preview>();
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  // 预检的请求序号：连点工作表时后发的可能先回，旧响应会把预览盖成另一个 sheet 的内容。
  const inspectSeq = useRef(0);

  /** 作废在途预检 + 清预览：任何"输入变了"的路径都要走它。 */
  const invalidatePreview = () => {
    inspectSeq.current += 1;
    setPreview(undefined);
    setBusy(false);
  };

  const reset = () => {
    setText('');
    setFile(undefined);
    setSheet('');
    setNote('');
    // busy 一起清：旧预检的 finally 因为序号不一致已经不会收尾（序号在这里 +1），
    // 不清的话重新打开时按钮会一直转。
    invalidatePreview();
  };
  // 关掉就清空：下次打开是干净的，不会带着上次的粘贴内容。
  useEffect(() => { if (!open) reset(); }, [open]);

  const upload = (selectedSheet = sheet) => {
    const data = new FormData();
    if (file) data.append('file', file);
    else if (text.trim()) data.append('text', text);
    if (selectedSheet) data.append('sheetName', selectedSheet);
    return data;
  };

  const inspect = async (selectedSheet = sheet) => {
    const seq = ++inspectSeq.current;
    setPreview(undefined);
    setBusy(true);
    try {
      const result = await request<Preview>('/api/option-sources/inspect',
        { method: 'POST', data: upload(selectedSheet) });
      if (seq !== inspectSeq.current) return;
      setPreview(result);
    } catch (error: any) {
      // 预检失败保持"没有预览"：不能留着上一张表/上一段文本的行让人以为可以导。
      if (seq !== inspectSeq.current) return;
      setPreview(undefined);
      message.error(error?.message ?? '预检失败');
    } finally {
      if (seq === inspectSeq.current) setBusy(false);
    }
  };

  const submit = async () => {
    setBusy(true);
    try {
      const data = upload();
      if (note.trim()) data.append('note', note.trim());
      await request(`/api/option-sources/${sourceId}/versions/import`,
        { method: 'POST', data });
      message.success('已导入待发布版本');
      onImported();
      onClose();
    } catch (error: any) {
      message.error(error?.message ?? '导入失败');
    } finally {
      setBusy(false);
    }
  };

  const hasInput = Boolean(file) || Boolean(text.trim());

  return (
    <Modal
      title="导入新版本"
      open={open}
      // 提交中不许关：旧请求落地后会清掉新会话的输入并把它关掉。
      onCancel={() => { if (!busy) onClose(); }}
      mask={{ closable: !busy }}
      keyboard={!busy}
      width={760}
      footer={[
        <Button key="cancel" disabled={busy} onClick={onClose}>取消</Button>,
        <Button key="inspect" loading={busy} disabled={!hasInput} onClick={() => void inspect()}>
          检查导入内容
        </Button>,
        <Button key="import" type="primary" disabled={!preview?.columns.length || busy}
          onClick={() => void submit()}>
          导入为待发布版本
        </Button>,
      ]}
    >
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Alert
          type="info"
          showIcon
          title="导入只生成「待发布」版本"
          description="发布后，钉在这一版之前的表单不受影响；要让某张表单用上新数据，得重新发布那张表单。"
        />
        <Typography.Text type="secondary">
          粘贴文本每行一个选项；表格可粘贴 Excel 单元格或上传 CSV、TXT、XLS、XLSX。表格首行为列名。
        </Typography.Text>
        <Input.TextArea value={text} rows={5} disabled={!!file}
          aria-label="粘贴选项文本"
          placeholder="一行一个选项，或直接粘贴多列 Excel 表格"
          onChange={(event) => { setText(event.target.value); invalidatePreview(); }} />
        <Space>
          <Button icon={<FileExcelOutlined />} onClick={() => fileInput.current?.click()}>
            选择文件导入
          </Button>
          {file && <>
            <Typography.Text>已选择：{file.name}</Typography.Text>
            <Button size="small" onClick={() => {
              // 工作表也要清：不清的话下次粘贴文本还会带上旧文件的 sheetName。
              setFile(undefined);
              setSheet('');
              invalidatePreview();
            }}>清除文件</Button>
          </>}
        </Space>
        <input ref={fileInput} type="file" aria-label="选择导入文件" accept=".txt,.csv,.xls,.xlsx"
          style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }} onChange={(event) => {
            setFile(event.target.files?.[0]);
            event.target.value = '';
            setText('');
            setSheet('');
            invalidatePreview();
          }} />
        {!!preview?.sheets?.length && preview.sheets.length > 1 && <Select aria-label="选择工作表"
          style={{ width: 240 }} placeholder="选择工作表" value={sheet || undefined}
          options={preview.sheets.map((value) => ({ value, label: value }))}
          onChange={(value) => { setSheet(value); void inspect(value); }} />}
        <Input value={note} maxLength={200} aria-label="变更说明"
          placeholder="变更说明（可选）：这一版改了什么，例如「新增港澳台」"
          onChange={(event) => setNote(event.target.value)} />
        {!hasInput && <Typography.Text type="secondary">先粘贴文本或选一个文件，再点「检查导入内容」。</Typography.Text>}
        {!!preview?.columns.length && <>
          <Typography.Text>
            共 {preview.rowCount} 行 · {preview.columns.length} 列，下面显示前 {preview.rows.length} 行
          </Typography.Text>
          <Table size="small" scroll={{ x: 'max-content', y: 260 }} pagination={false}
            // 包一层再给行号做 key：既不能往原始行里塞字段（列名恰好叫 key 时会盖掉它，
            // 预览显示行号、导入存的却是原值），也不能用 rowKey 的 index 参数（antd 6 已弃用）。
            // 行内容可能重复（选项允许重复），所以不能用内容做 key。
            rowKey={(item) => String(item.index)}
            dataSource={preview.rows.map((row, index) => ({ index, row }))}
            columns={preview.columns.map((column) => ({
              title: column, dataIndex: ['row', column], ellipsis: true,
            }))} />
        </>}
      </Space>
    </Modal>
  );
}
