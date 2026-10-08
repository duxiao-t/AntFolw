import { Alert, Drawer, Empty, Table, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';

type Version = { id: number; versionNo: number; columns: string[]; rowCount: number };
type Row = { row_no: number; data: Record<string, unknown> };

const LIMIT = 100;

/** 发布完能用它核对数据——以前这个接口存在但页面上没有入口。 */
export function OptionSourceRowsDrawer({ sourceId, version, onClose }: {
  sourceId: number;
  version: Version | null;
  onClose(): void;
}) {
  const rows = useQuery<Row[]>({
    queryKey: ['option-source-rows', sourceId, version?.id],
    enabled: Boolean(version),
    queryFn: () => request<Row[]>(
      `/api/option-sources/${sourceId}/versions/${version?.id}/rows?limit=${LIMIT}`),
  });
  const columns = version?.columns ?? [];

  return (
    <Drawer
      title={version ? `v${version.versionNo} 的数据` : ''}
      open={Boolean(version)}
      onClose={onClose}
      size={760}
    >
      {rows.isPending && <Typography.Text type="secondary">加载中…</Typography.Text>}
      {rows.error && <Alert type="error" showIcon title={`无法加载数据：${(rows.error as Error).message}`} />}
      {rows.data && rows.data.length === 0 && <Empty description="这个版本没有数据" />}
      {rows.data && rows.data.length > 0 && <>
        <Table size="small" rowKey="row_no" pagination={false} scroll={{ x: 'max-content', y: 'calc(100vh - 220px)' }}
          dataSource={rows.data}
          columns={columns.map((column) => ({
            title: column,
            dataIndex: ['data', column],
            ellipsis: true,
            render: (value: unknown) => (value == null || value === '' ? '—' : String(value)),
          }))} />
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
          共 {version?.rowCount} 行，这里只显示前 {LIMIT} 行。
        </Typography.Text>
      </>}
    </Drawer>
  );
}
