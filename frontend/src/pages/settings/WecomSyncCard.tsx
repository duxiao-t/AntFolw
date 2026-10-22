import { CloudSyncOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Progress, Radio, Space, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import { active, phaseLabels, statusMeta, WECOM_GREEN } from './wecomModel';
import type { SyncJob, SyncMode } from './wecomModel';
import { useWecomStyles } from './wecomStyles';

type Props = {
  job?: SyncJob;
  loading: boolean;
  /** 已配置 Secret、无未保存修改、且没有活跃任务。 */
  canStart: boolean;
  syncMode: SyncMode;
  onChangeMode: (mode: SyncMode) => void;
  starting: boolean;
  onStart: () => void;
};

function Metric({ label, value }: { label: string; value: number | string }) {
  const { styles } = useWecomStyles();
  return (
    <div className={styles.metric}>
      <strong className={styles.metricValue}>{value}</strong>
      <span className={styles.metricLabel}>{label}</span>
    </div>
  );
}

/** 任务卡片：有任务时看进度与结果，无任务时给一个空态入口。 */
export function WecomSyncCard({ job, loading, canStart, syncMode, onChangeMode, starting, onStart }: Props) {
  const { styles } = useWecomStyles();
  const currentStatus = job ? statusMeta[job.status] : undefined;
  const alertType = job?.status === 'FAILED' ? 'error' : 'warning';

  return (
    <Card
      className={styles.card}
      loading={loading}
      title={(
        <span className={styles.cardTitle}>
          <span className={styles.icon}><CloudSyncOutlined /></span>
          当前同步任务
        </span>
      )}
      extra={currentStatus && <Tag color={currentStatus.color}>{currentStatus.label}</Tag>}
    >
      {job ? (
        <>
          <div className={styles.progressHeader}>
            <div>
              <Typography.Text strong>{phaseLabels[job.phase] ?? job.phase}</Typography.Text>
              <Typography.Text className={styles.phase}> · {job.message}</Typography.Text>
            </div>
            <Typography.Text strong>{job.percent}%</Typography.Text>
          </div>
          <Progress
            percent={job.percent}
            showInfo={false}
            strokeColor={job.status === 'FAILED' ? undefined : WECOM_GREEN}
            status={job.status === 'FAILED' ? 'exception' : job.status === 'SUCCESS' ? 'success' : 'active'}
          />
          <div className={styles.metrics}>
            <Metric label="处理进度" value={`${job.processedUsers}/${job.totalUsers}`} />
            <Metric label="新增成员" value={job.createdUsers} />
            <Metric label="更新成员" value={job.updatedUsers} />
            <Metric label="失败成员" value={job.failedUsers} />
          </div>
          {(job.status === 'PARTIAL' || job.status === 'FAILED') && (
            <Alert
              showIcon
              type={alertType}
              title={job.status === 'FAILED' ? '同步未完成' : '部分数据需要处理'}
              description={job.errorSummary.length
                ? job.errorSummary.join('；')
                : '请检查企微凭证、通讯录权限或成员联系方式后重新同步。'}
            />
          )}
          {job.status === 'SUCCESS' && (
            <Alert showIcon type="success" title="通讯录已同步完成" />
          )}
          <div className={styles.footer}>
            <span className={styles.time}>
              {job.finishedAt
                ? `完成于 ${dayjs(job.finishedAt).format('YYYY-MM-DD HH:mm:ss')}`
                : job.startedAt
                  ? `开始于 ${dayjs(job.startedAt).format('YYYY-MM-DD HH:mm:ss')}`
                  : '等待执行器接收任务'}
            </span>
            <Space>
              <Radio.Group
                value={syncMode}
                onChange={(event) => onChangeMode(event.target.value)}
                disabled={!canStart || starting}
              >
                <Radio.Button value="INCREMENTAL">增量同步</Radio.Button>
                <Radio.Button value="FULL">全量同步</Radio.Button>
              </Radio.Group>
              <Button
                type="primary"
                icon={<CloudSyncOutlined />}
                disabled={!canStart}
                loading={starting}
                onClick={onStart}
              >
                {active(job) ? '同步进行中' : '开始同步'}
              </Button>
            </Space>
          </div>
        </>
      ) : (
        <div className={styles.empty}>
          <Space orientation="vertical" size="small">
            <CloudSyncOutlined className={styles.emptyIcon} />
            <Typography.Text strong>尚未执行通讯录同步</Typography.Text>
            <Typography.Text type="secondary">保存连接配置后即可创建持久化同步任务</Typography.Text>
            <Space>
              <Radio.Group
                value={syncMode}
                onChange={(event) => onChangeMode(event.target.value)}
                disabled={!canStart || starting}
              >
                <Radio.Button value="INCREMENTAL">增量同步</Radio.Button>
                <Radio.Button value="FULL">全量同步</Radio.Button>
              </Radio.Group>
              <Button
                type="primary"
                icon={<CloudSyncOutlined />}
                disabled={!canStart}
                loading={starting}
                onClick={onStart}
                style={{ marginTop: 10 }}
              >
                开始同步
              </Button>
            </Space>
          </Space>
        </div>
      )}
    </Card>
  );
}
