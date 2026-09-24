import { FileExcelOutlined } from '@ant-design/icons';
import { App, Button, Input, Modal, Select, Space, Table, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { request } from '@umijs/max';

type Preview = {
  sheets: string[];
  columns: string[];
  rowCount: number;
  rows: Array<Record<string, string>>;
};

/**
 * 导入新版本：粘贴文本或上传表格 → 预览 → 存成待发布版本。
 * 三个阶段收在一个 Modal 里，页面上就只剩「版本」一件事要看。
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
  const [preview, setPreview] = useState<Preview>();
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  // 预检的请求序号：连点工作表时后发的可能先回，旧响应会把预览盖成另一个 sheet 的内容，
  // 而「导入」按的是当前 sheet——预览与实际导入的内容就对不上了。只认最后一次。
  const inspectSeq = useRef(0);

  const reset = () => {
    setText('');
    setFile(undefined);
    setSheet('');
    setPreview(undefined);
    // 关掉/重置后落地的旧预检响应不该再写回状态。
    inspectSeq.current += 1;
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
    setBusy(true);
    try {
      const result = await request<Preview>('/api/option-sources/inspect',
        { method: 'POST', data: upload(selectedSheet) });
      if (seq !== inspectSeq.current) return;
      setPreview(result);
    } catch (error: any) {
      if (seq !== inspectSeq.current) return;
      message.error(error?.message ?? '预检失败');
    } finally {
      if (seq === inspectSeq.current) setBusy(false);
    }
  };

  const submit = async () => {
    setBusy(true);
    try {
      await request(`/api/option-sources/${sourceId}/versions/import`,
        { method: 'POST', data: upload() });
      message.success('已导入待发布版本');
      reset();
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
      onCancel={onClose}
      width={760}
      footer={[
        <Button key="cancel" onClick={onClose}>取消</Button>,
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
        <Typography.Text type="secondary">
          粘贴文本每行一个选项；表格可粘贴 Excel 单元格或上传 CSV、TXT、XLS、XLSX。表格首行为列名。
        </Typography.Text>
        <Input.TextArea value={text} rows={5} disabled={!!file}
          aria-label="粘贴选项文本"
          placeholder="一行一个选项，或直接粘贴多列 Excel 表格"
          onChange={(event) => { setText(event.target.value); setPreview(undefined); }} />
        <Space>
          <Button icon={<FileExcelOutlined />} onClick={() => fileInput.current?.click()}>
            选择文件导入
          </Button>
          {file && <>
            <Typography.Text>已选择：{file.name}</Typography.Text>
            <Button size="small" onClick={() => { setFile(undefined); setPreview(undefined); }}>清除文件</Button>
          </>}
        </Space>
        <input ref={fileInput} type="file" aria-label="选择导入文件" accept=".txt,.csv,.xls,.xlsx"
          style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }} onChange={(event) => {
            setFile(event.target.files?.[0]);
            event.target.value = '';
            setText('');
            setSheet('');
            setPreview(undefined);
          }} />
        {!!preview?.sheets?.length && preview.sheets.length > 1 && <Select aria-label="选择工作表"
          style={{ width: 240 }} placeholder="选择工作表" value={sheet || undefined}
          options={preview.sheets.map((value) => ({ value, label: value }))}
          onChange={(value) => { setSheet(value); void inspect(value); }} />}
        {!hasInput && <Typography.Text type="secondary">先粘贴文本或选一个文件，再点「检查导入内容」。</Typography.Text>}
        {!!preview?.columns.length && <>
          <Typography.Text>
            共 {preview.rowCount} 行 · {preview.columns.length} 列，下面显示前 {preview.rows.length} 行
          </Typography.Text>
          <Table size="small" scroll={{ x: 'max-content', y: 260 }} pagination={false}
            rowKey={(row) => String(row.key)}
            dataSource={preview.rows.map((row, index) => ({ ...row, key: index }))}
            columns={preview.columns.map((column) => ({ title: column, dataIndex: column, ellipsis: true }))} />
        </>}
        <Typography.Text type="secondary">
          导入只生成待发布版本，不影响正在用它的表单。发布后，重新发布那张表单才会用上新数据。
        </Typography.Text>
      </Space>
    </Modal>
  );
}
