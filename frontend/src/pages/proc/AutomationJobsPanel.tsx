import { RedoOutlined } from '@ant-design/icons';
import { Button, Table, Tag, Typography } from 'antd';
import { formatDateTime, nodeLabel, type ProcessNode } from './detailPresentation';
import { SectionHeading } from './detailSections';

/** 自动化作业列表：延时 / Webhook 任务的执行状态与重试入口。 */
export function AutomationJobsPanel({
  jobs,
  snapshotObj,
  canRetry,
  onRetry,
}: {
  jobs: any[];
  snapshotObj: ProcessNode | null;
  canRetry: boolean;
  onRetry(jobId: number): void;
}) {
  return (
    <section className="process-detail-panel" aria-labelledby="automation-title">
      <SectionHeading
        kicker="AUTOMATION"
        title="自动化作业"
        id="automation-title"
        extra={`${jobs.length} 项`}
      />
      <div className="process-detail-table-scroll">
        <Table
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={jobs}
          scroll={{ x: 840 }}
          expandable={{
            rowExpandable: (row: any) =>
              row.jobType === 'TRIGGER' && Boolean(row.responseBody),
            expandedRowRender: (row: any) => (
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>
                {row.responseBody}
              </pre>
            ),
          }}
          columns={[
            {
              title: '节点',
              dataIndex: 'nodeId',
              minWidth: 180,
              render: (value: string, row: any) => (
                <div className="process-primary-cell">
                  <strong>{nodeLabel(snapshotObj, value)}</strong>
                  <small>{row.jobType === 'DELAY' ? '延时任务' : 'Webhook 自动任务'}</small>
                </div>
              ),
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (value: string) => (
                <Tag color={statusTagColor(value)}>{automationStatusLabel(value)}</Tag>
              ),
            },
            {
              title: '执行',
              width: 160,
              render: (_: unknown, row: any) =>
                `${row.attempts}/${row.maxAttempts} · ${row.blocking ? '成功后继续' : '发送后继续'}`,
            },
            {
              title: '计划时间',
              dataIndex: 'scheduledAt',
              width: 180,
              render: formatDateTime,
            },
            {
              title: 'HTTP',
              dataIndex: 'responseStatus',
              width: 80,
              render: (value: number | null) => (value != null ? value : '—'),
            },
            {
              title: '结果',
              minWidth: 200,
              render: (_: unknown, row: any) => {
                // 成功也要显示响应体：企业微信等错误时 HTTP 200，真正原因在 errcode 里。
                const text = row.lastError || row.responseBody;
                return text
                  ? <Typography.Text ellipsis={{ tooltip: text }}>{text}</Typography.Text>
                  : '—';
              },
            },
            {
              title: '操作',
              width: 92,
              fixed: 'right',
              render: (_: unknown, row: any) =>
                canRetry && row.status === 'FAILED' ? (
                  <Button
                    size="small"
                    icon={<RedoOutlined />}
                    onClick={() => onRetry(row.id)}
                  >
                    重试
                  </Button>
                ) : null,
            },
          ]}
        />
      </div>
    </section>
  );
}

function statusTagColor(status: string): string {
  switch (status) {
    case 'APPROVED':
    case 'SUCCEEDED':
      return 'green';
    case 'REJECTED':
    case 'FAILED':
      return 'red';
    case 'CC':
      return 'cyan';
    case 'PENDING':
    case 'RUNNING':
      return 'blue';
    case 'SCHEDULED':
      return 'gold';
    default:
      return 'default';
  }
}

function automationStatusLabel(status: string) {
  return ({
    SCHEDULED: '等待执行',
    RUNNING: '执行中',
    SUCCEEDED: '已成功',
    FAILED: '执行失败',
    CANCELLED: '已取消',
  } as Record<string, string>)[status] ?? status;
}
