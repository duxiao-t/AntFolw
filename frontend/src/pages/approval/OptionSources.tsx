import { FileExcelOutlined, PlusOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, request } from '@umijs/max';
import { App, Button, Card, Input, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { AssigneePicker } from '../../components/AssigneePicker';

type Summary = { id: number; code: string; name: string; status: string; version: number;
  publishedVersionId?: number; publishedVersionNo?: number; rowCount?: number; draftVersionId?: number };
type Version = { id: number; versionNo: number; status: string; columns: string[]; rowCount: number; originalName: string };
type Detail = { source: Summary; versions: Version[]; userIds: number[]; roleIds: number[]; deletable: boolean };
type Preview = { sheets: string[]; columns: string[]; rowCount: number; rows: Array<Record<string, string>> };

export default function OptionSources() {
  const { message } = App.useApp();
  const [list, setList] = useState<Summary[]>([]);
  const [detail, setDetail] = useState<Detail>();
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [text, setText] = useState('');
  const [file, setFile] = useState<File>();
  const fileInput = useRef<HTMLInputElement>(null);
  const [sheet, setSheet] = useState('');
  const [preview, setPreview] = useState<Preview>();
  const [users, setUsers] = useState<number[]>([]);
  const [roles, setRoles] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);

  const refresh = async (id?: number) => {
    const sources = await request<Summary[]>('/api/option-sources');
    setList(sources);
    if (id) {
      const current = await request<Detail>(`/api/option-sources/${id}`);
      setDetail(current);
      setUsers(current.userIds);
      setRoles(current.roleIds);
    }
  };
  useEffect(() => { void refresh().catch((error) => message.error(error?.message ?? '无法加载数据源')); }, []);
  const open = (id: number) => void refresh(id).catch((error) => message.error(error?.message ?? '无法打开数据源'));
  const run = async (action: () => Promise<number | undefined>, success: string, clear = false) => {
    setBusy(true);
    try {
      const id = await action();
      if (clear) setDetail(undefined);
      await refresh(clear ? undefined : id ?? detail?.source.id);
      message.success(success);
    }
    catch (error: any) { message.error(error?.message ?? '操作失败'); }
    finally { setBusy(false); }
  };
  const upload = (selectedSheet = sheet) => {
    const data = new FormData();
    if (file) data.append('file', file);
    else if (text.trim()) data.append('text', text);
    if (selectedSheet) data.append('sheetName', selectedSheet);
    return data;
  };
  const inspect = async (selectedSheet = sheet) => {
    setBusy(true);
    try { setPreview(await request<Preview>('/api/option-sources/inspect', { method: 'POST', data: upload(selectedSheet) })); }
    catch (error: any) { message.error(error?.message ?? '预检失败'); }
    finally { setBusy(false); }
  };

  return (
    <PageContainer title="选项数据源" subTitle="导入一次，多个表单可引用；表单发布时固定所选版本"
      onBack={() => history.push('/approval/forms')}>
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(230px, 300px) minmax(0, 1fr)', gap: 16 }}>
        <Card title="已导入的数据" size="small">
          <Space direction="vertical" style={{ width: '100%' }}>
            {list.map((source) => (
              <Button key={source.id} block type={detail?.source.id === source.id ? 'primary' : 'default'}
                onClick={() => open(source.id)} style={{ textAlign: 'left', height: 'auto', whiteSpace: 'normal' }}>
                {source.name} {source.publishedVersionNo ? `· v${source.publishedVersionNo}` : '· 未发布'}
              </Button>
            ))}
            {!list.length && <Typography.Text type="secondary">创建数据源后，可导入文本或 Excel 表格。</Typography.Text>}
          </Space>
        </Card>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Card title="创建数据源" size="small">
            <Space wrap>
              <Input aria-label="数据源名称" placeholder="名称，例如设备台账" maxLength={128} value={name} onChange={(e) => setName(e.target.value)} />
              <Input aria-label="数据源编码" placeholder="英文编码，例如 device_codes" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} />
              <Button type="primary" icon={<PlusOutlined />} disabled={!name.trim() || !code.trim()} loading={busy}
                onClick={() => void run(async () => {
                  const created = await request<Detail>('/api/option-sources', { method: 'POST', data: { name: name.trim(), code: code.trim() } });
                  setName(''); setCode('');
                  return created.source.id;
                }, '数据源已创建')}>
                创建
              </Button>
            </Space>
          </Card>
          {detail && <>
            <Card title={<Space>{detail.source.name} <Tag>{detail.source.status === 'ACTIVE' ? '可使用' : '已停用'}</Tag>
              {detail.deletable && <Tag color="blue">未引用 · 未发布</Tag>}</Space>}
              extra={<Space>
                {detail.deletable && <Popconfirm title="确定删除此未引用、未发布的数据源及其草稿？"
                  onConfirm={() => void run(async () => { await request(`/api/option-sources/${detail.source.id}`, { method: 'DELETE' }); }, '数据源已删除', true)}>
                  <Button danger size="small" loading={busy}>删除</Button>
                </Popconfirm>}
                {detail.source.status === 'ACTIVE' && <Popconfirm title="停用后现有已发布表单仍可使用当前版本，确认停用？"
                  onConfirm={() => void run(async () => { await request(`/api/option-sources/${detail.source.id}/disable`, { method: 'POST' }); }, '已停用')}>
                  <Button danger size="small">停用</Button>
                </Popconfirm>}
              </Space>}>
              <Typography.Paragraph type="secondary">版本固定：重新导入和发布数据不会改变现有表单的选项；要切换数据，需重新发布表单。</Typography.Paragraph>
              <Table size="small" rowKey="id" pagination={false} dataSource={detail.versions}
                columns={[
                  { title: '版本', dataIndex: 'versionNo', render: (v: number) => `v${v}` },
                  { title: '状态', dataIndex: 'status', render: (s: string) => s === 'PUBLISHED' ? '已发布' : '待发布' },
                  { title: '记录', dataIndex: 'rowCount' },
                  { title: '文件', dataIndex: 'originalName', ellipsis: true },
                  { title: '操作', render: (_: unknown, v: Version) => v.status === 'DRAFT'
                    ? <Popconfirm title="确认发布此数据版本？" onConfirm={() => void run(async () => { await request(`/api/option-sources/${detail.source.id}/versions/${v.id}/publish`, { method: 'POST' }); }, '数据版本已发布')}>
                        <Button size="small" type="primary">发布数据</Button>
                      </Popconfirm> : null },
                ]} />
            </Card>
            {detail.source.status === 'ACTIVE' && <Card title={<Space><FileExcelOutlined />导入新版本</Space>}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Typography.Text type="secondary">粘贴文本每行一个选项；表格可粘贴 Excel 单元格或上传 CSV、TXT、XLS、XLSX。表格首行为列名。</Typography.Text>
                <Input.TextArea value={text} rows={4} placeholder="一行一个选项，或直接粘贴多列 Excel 表格" disabled={!!file}
                  onChange={(e) => { setText(e.target.value); setPreview(undefined); }} />
                <Button icon={<FileExcelOutlined />} style={{ alignSelf: 'flex-start' }} onClick={() => fileInput.current?.click()}>
                  选择文件导入
                </Button>
                <input ref={fileInput} type="file" aria-label="选择导入文件" accept=".txt,.csv,.xls,.xlsx"
                  style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }} onChange={(e) => {
                    setFile(e.target.files?.[0]); e.target.value = '';
                    setText(''); setSheet(''); setPreview(undefined);
                  }} />
                {file && <Space><Typography.Text>已选择：{file.name}</Typography.Text>
                  <Button size="small" onClick={() => { setFile(undefined); setPreview(undefined); }}>清除文件</Button></Space>}
                {preview?.sheets && preview.sheets.length > 1 && <Select aria-label="选择工作表" style={{ width: 240 }}
                  placeholder="选择工作表" value={sheet || undefined} options={preview.sheets.map((v) => ({ value: v, label: v }))}
                  onChange={(v) => { setSheet(v); void inspect(v); }} />}
                <Space>
                  <Button disabled={!file && !text.trim()} loading={busy} onClick={() => void inspect()}>检查导入内容</Button>
                  <Button type="primary" disabled={!preview?.columns.length || busy}
                    onClick={() => void run(async () => {
                      await request(`/api/option-sources/${detail.source.id}/versions/import`, { method: 'POST', data: upload() });
                      setPreview(undefined); setFile(undefined); setText(''); setSheet('');
                    }, '已导入草稿，请发布数据版本')}>
                    导入为草稿
                  </Button>
                </Space>
                {preview && !!preview.columns.length && <>
                  <Typography.Text>共 {preview.rowCount} 行 · {preview.columns.length} 列，下面显示前 {preview.rows.length} 行</Typography.Text>
                  <Table size="small" scroll={{ x: 'max-content' }} pagination={false}
                    dataSource={preview.rows.map((row, index) => ({ ...row, key: index }))}
                    columns={preview.columns.map((column) => ({ title: column, dataIndex: column, ellipsis: true }))} />
                </>}
              </Space>
            </Card>}
            <Card title="允许哪些表单维护者引用">
              <Typography.Paragraph type="secondary">管理员始终可引用；其他维护者需先获得用户或角色授权。</Typography.Paragraph>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Typography.Text>用户（输入姓名、账号或部门搜索）</Typography.Text>
                <AssigneePicker mode="user" value={users} onChange={setUsers} />
                <Typography.Text>角色（输入名称或编码搜索）</Typography.Text>
                <AssigneePicker mode="role" value={roles} onChange={setRoles} />
                <Button loading={busy} onClick={() => void run(async () => { await request(`/api/option-sources/${detail.source.id}/grants`, {
                  method: 'PUT', data: { version: detail.source.version, userIds: users, roleIds: roles },
                }); }, '引用权限已保存')}>保存引用权限</Button>
              </Space>
            </Card>
          </>}
        </Space>
      </div>
    </PageContainer>
  );
}
