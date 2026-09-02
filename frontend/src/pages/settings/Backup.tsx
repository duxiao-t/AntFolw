import { DeleteOutlined, DownloadOutlined, PlusOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { App, Button, Card, Form, InputNumber, Popconfirm, Space, Switch, Table, TimePicker, Typography } from 'antd';
import dayjs from 'dayjs';

type Settings = { enabled: boolean; localTime: string; retentionDays: number; version: number };
type BackupFile = { name: string; bytes: number; createdAt: string };
type Status = { running: boolean; error?: string; latest?: BackupFile };

export default function BackupPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({ queryKey: ['backup-settings'], queryFn: () => request<Settings>('/api/system/backups/settings') });
  const filesQuery = useQuery({ queryKey: ['backups'], queryFn: () => request<BackupFile[]>('/api/system/backups'), refetchInterval: 10_000 });
  const statusQuery = useQuery({ queryKey: ['backup-status'], queryFn: () => request<Status>('/api/system/backups/status'), refetchInterval: (query) => query.state.data?.running ? 2_000 : 10_000 });
  const save = useMutation({ mutationFn: (values: any) => request<Settings>('/api/system/backups/settings', { method: 'PUT', data: { ...values, localTime: values.localTime.format('HH:mm:ss'), version: settingsQuery.data?.version } }), onSuccess: (result) => { queryClient.setQueryData(['backup-settings'], result); message.success('备份计划已保存'); } });
  const create = useMutation({ mutationFn: () => request<Status>('/api/system/backups', { method: 'POST' }), onSuccess: () => { void statusQuery.refetch(); message.success('备份任务已启动'); } });
  const remove = useMutation({ mutationFn: (name: string) => request(`/api/system/backups/${encodeURIComponent(name)}`, { method: 'DELETE' }), onSuccess: () => { void filesQuery.refetch(); message.success('备份已删除'); } });
  const download = async (name: string) => {
    const token = localStorage.getItem('antflow-token');
    const response = await fetch(`/api/system/backups/${encodeURIComponent(name)}/download`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) { message.error('备份下载失败'); return; }
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = name; anchor.click(); URL.revokeObjectURL(url);
  };
  const settings = settingsQuery.data;
  return <PageContainer title="系统备份" subTitle="加密备份 PostgreSQL、附件与审计归档；恢复需停机执行运维脚本">
    <Card title="自动备份计划" loading={settingsQuery.isLoading}>
      {settings && <Form layout="inline" initialValues={{ enabled: settings.enabled, localTime: dayjs(`2000-01-01T${settings.localTime}`), retentionDays: settings.retentionDays }} onFinish={(values) => save.mutate(values)}>
        <Form.Item name="enabled" label="自动备份" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="localTime" label="每日执行" rules={[{ required: true }]}><TimePicker format="HH:mm" minuteStep={5} /></Form.Item>
        <Form.Item name="retentionDays" label="保留天数" rules={[{ required: true }]}><InputNumber min={1} max={365} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={save.isPending}>保存计划</Button>
      </Form>}
    </Card>
    <Card style={{ marginTop: 16 }} title="备份文件" extra={<Button type="primary" icon={<PlusOutlined />} disabled={statusQuery.data?.running} loading={create.isPending || statusQuery.data?.running} onClick={() => create.mutate()}>{statusQuery.data?.running ? '备份中' : '立即备份'}</Button>}>
      {statusQuery.data?.error && <Typography.Paragraph type="danger">{statusQuery.data.error}</Typography.Paragraph>}
      <Typography.Paragraph type="secondary">备份文件使用独立密钥强制加密。请把下载文件与密钥分别保存到异机。</Typography.Paragraph>
      <Table<BackupFile> rowKey="name" loading={filesQuery.isLoading} dataSource={filesQuery.data ?? []} pagination={{ pageSize: 20 }} columns={[
        { title: '文件名', dataIndex: 'name' },
        { title: '创建时间', dataIndex: 'createdAt', render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
        { title: '大小', dataIndex: 'bytes', render: (value) => `${(Number(value) / 1024 / 1024).toFixed(2)} MB` },
        { title: '操作', render: (_, file) => <Space><Button icon={<DownloadOutlined />} onClick={() => void download(file.name)}>下载</Button><Popconfirm title="确认删除此备份？" onConfirm={() => remove.mutate(file.name)}><Button danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Space> },
      ]} />
    </Card>
  </PageContainer>;
}
