import { PageContainer } from '@ant-design/pro-components';
import { useMutation, useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Row,
  Space,
  Skeleton,
  Table,
  Tag,
  Typography,
} from 'antd';
import { createStyles } from 'antd-style';

type StorageConfig = {
  storage: string;
  endpoint: string;
  bucket: string;
  region: string;
  createBucket: boolean;
  accessKeyMasked: string;
  secretConfigured: boolean;
  maxFileBytes: number;
  maxVideoBytes: number;
};

type StorageProbe = {
  ok: boolean;
  bucketExists: boolean;
  writable: boolean;
  elapsedMs: number;
  detail: string;
};

/** 每项配置对应的环境变量：管理员据此知道该去哪里改，而不是在页面上找开关。 */
const CONFIG_SOURCES = [
  { key: 'endpoint', label: 'Endpoint', env: 'MINIO_ENDPOINT', hint: '对象存储服务地址，容器内用服务名（http://minio:9000）' },
  { key: 'bucket', label: 'Bucket', env: 'MINIO_BUCKET', hint: '附件与媒体文件所在桶' },
  { key: 'region', label: 'Region', env: 'MINIO_REGION', hint: 'AWS S3 等云存储才需要；MinIO 留空' },
  { key: 'accessKey', label: 'Access Key', env: 'MINIO_ACCESS_KEY', hint: '访问密钥，页面上只显示前 4 位' },
  { key: 'secretKey', label: 'Secret Key', env: 'MINIO_SECRET_KEY', hint: '密钥，只回传"是否已配置"，不回传内容' },
  { key: 'createBucket', label: '自动创建桶', env: 'MINIO_CREATE_BUCKET', hint: '桶不存在时由后端首次上传自动创建' },
  { key: 'storage', label: '存储实现', env: 'MOBILE_FILE_STORAGE', hint: 'minio=对象存储；local=落本机磁盘（仅开发）' },
];

const useStyles = createStyles(({ token }) => ({
  probe: {
    marginTop: 16,
    padding: '12px 14px',
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadius,
    background: token.colorFillQuaternary,
  },
  probeHead: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' as const },
  mono: { fontFamily: token.fontFamilyCode, fontSize: 13 },
}));

/** 人类可读容量：≥1 GB 保留一位小数，其余取整到 MB——避免"104857600"这种扫读困难的原始字节数。 */
function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '未限制';
  const mib = bytes / 1024 / 1024;
  return mib >= 1024 ? `${(mib / 1024).toFixed(1)} GB` : `${Math.round(mib)} MB`;
}

export default function S3StoragePage() {
  const { styles } = useStyles();
  const { message } = App.useApp();

  const config = useQuery({
    queryKey: ['storage-config'],
    queryFn: () => request<StorageConfig>('/api/settings/storage'),
    retry: 0,
  });

  const probe = useMutation({
    mutationFn: () => request<StorageProbe>('/api/settings/storage/test', { method: 'POST' }),
    onError: () => message.error('自检请求失败，请检查登录状态后重试'),
  });

  const data = config.data;
  const result = probe.data;

  return (
    <PageContainer
      header={{ title: 'S3 存储', subTitle: '对象存储配置一览与连通性自检' }}
      content={
        <Alert
          type="info"
          showIcon
          message="存储配置由环境变量提供，运行期只读"
          description="这里的值来自后端进程的 MINIO_* 环境变量，页面上修改不会生效；如需变更，请修改部署环境变量后重启后端。自检会真实写入并立即删除一个探针对象，用来验证桶与读写权限。"
        />
      }
    >
      {config.isError ? (
        <Alert
          type="error"
          showIcon
          message="存储配置加载失败"
          action={<Button size="small" onClick={() => config.refetch()}>重试</Button>}
        />
      ) : null}

      <Row gutter={[24, 24]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={14}>
          <Card title="当前生效配置" loading={config.isPending}>
            {data ? (
              <>
                <Descriptions column={1} size="small" colon={false}
                  items={[
                    { key: 'storage', label: '存储实现', children: <Tag color={data.storage === 'minio' ? 'blue' : 'orange'}>{data.storage}</Tag> },
                    { key: 'endpoint', label: 'Endpoint', children: <span className={styles.mono}>{data.endpoint || '未配置'}</span> },
                    { key: 'bucket', label: 'Bucket', children: <span className={styles.mono}>{data.bucket || '未配置'}</span> },
                    { key: 'region', label: 'Region', children: <span className={styles.mono}>{data.region || '未设置（MinIO 默认）'}</span> },
                    { key: 'accessKey', label: 'Access Key', children: <span className={styles.mono}>{data.accessKeyMasked || '未配置'}</span> },
                    {
                      key: 'secretKey',
                      label: 'Secret Key',
                      children: data.secretConfigured
                        ? <Tag color="green">已配置</Tag>
                        : <Tag color="red">未配置，上传会失败</Tag>,
                    },
                    { key: 'createBucket', label: '自动创建桶', children: data.createBucket ? '开启' : '关闭' },
                    { key: 'limits', label: '上传上限', children: `图片/文件 ${formatBytes(data.maxFileBytes)} · 视频 ${formatBytes(data.maxVideoBytes)}` },
                  ]}
                />

                <div className={styles.probe}>
                  <div className={styles.probeHead}>
                    <Button type="primary" loading={probe.isPending} onClick={() => probe.mutate()}>
                      测试连接
                    </Button>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      会写入并立即删除 <span className={styles.mono}>_healthcheck/</span> 下的一个探针对象
                    </Typography.Text>
                  </div>
                  {result ? (
                    <Space direction="vertical" size={6} style={{ marginTop: 12 }}>
                      {/* 四个标签刻意分开：ok 是合成结论，后面三个是定位线索（桶在不在 / 能不能写 / 快不快）。 */}
                      <Space wrap>
                        <Tag color={result.ok ? 'green' : 'red'}>{result.ok ? '可用' : '不可用'}</Tag>
                        <Tag>{result.bucketExists ? '桶已存在' : '桶不存在'}</Tag>
                        <Tag>{result.writable ? '可写' : '不可写'}</Tag>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>耗时 {result.elapsedMs} ms</Typography.Text>
                      </Space>
                      <Typography.Text style={{ fontSize: 13 }}>{result.detail}</Typography.Text>
                    </Space>
                  ) : null}
                </div>
              </>
            ) : config.isPending ? (
              <Skeleton active paragraph={{ rows: 6 }} />
            ) : null}
          </Card>
        </Col>

        <Col xs={24} xl={10}>
          <Card title="配置项来源" loading={config.isPending}>
            <Table
              rowKey="key"
              size="small"
              pagination={false}
              dataSource={CONFIG_SOURCES}
              columns={[
                { title: '配置项', dataIndex: 'label', width: 110 },
                { title: '环境变量', dataIndex: 'env', render: (env: string) => <span className={styles.mono}>{env}</span> },
                { title: '说明', dataIndex: 'hint' },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </PageContainer>
  );
}
