import {
  AlertOutlined,
  ClockCircleOutlined,
  InboxOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { Button, Empty, Result, Skeleton, Table, Tag } from 'antd';
import dayjs from 'dayjs';
import '../Welcome.css';

type StuckInstance = {
  id: number;
  current_node_id?: string;
  started_at: string;
};

type OverdueTask = {
  task_id: number;
  instance_id: number;
  node_id: string;
  assignee_id: number;
  timeout_at: string;
};

type RejectionRate = {
  node_id: string;
  rejected: number;
  decided: number;
  reject_rate?: number;
};

type WorkflowMonitorOverview = {
  stuckInstances: StuckInstance[];
  overdueTasks: OverdueTask[];
  nodeRejectionRates: RejectionRate[];
  outbox: { pending: number; dead: number; oldest_pending?: string };
};

const empty = (text: string) => (
  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />
);

export default function WorkflowMonitor() {
  const { data, isLoading, isFetching, isError, refetch } =
    useQuery<WorkflowMonitorOverview>({
      queryKey: ['workflow-monitor'],
      queryFn: () => request('/api/workflow-monitor?limit=50'),
      refetchInterval: 30_000,
    });

  if (isLoading) {
    return (
      <div className="workplace-page">
        <div className="workplace-loading">
          <Skeleton active paragraph={{ rows: 10 }} />
        </div>
      </div>
    );
  }
  if (isError || !data) {
    return (
      <div className="workplace-page">
        <Result
          status="error"
          title="流程监控数据不可用"
          subTitle="确认审批服务已启动并完成数据库迁移后重新加载。"
          extra={
            <Button type="primary" icon={<ReloadOutlined />} onClick={() => refetch()}>
              重新加载
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="workplace-page">
      <header className="workplace-header">
        <div>
          <p className="workplace-kicker">ANTFLOW / FLOW CONTROL</p>
          <h1>流程运行监控</h1>
          <p className="workplace-subtitle">先处理阻塞与超时，再观察节点质量和消息积压。</p>
        </div>
        <div className="workplace-header-actions">
          <Button icon={<ReloadOutlined spin={isFetching} />} loading={isFetching} onClick={() => refetch()}>
            刷新监控
          </Button>
        </div>
      </header>

      <section className="metric-grid" aria-label="流程风险指标">
        <article className="metric metric-danger">
          <span className="metric-label"><AlertOutlined />卡死实例</span>
          <strong>{data.stuckInstances.length}</strong>
          <small>运行中但没有可执行任务</small>
        </article>
        <article className="metric metric-danger">
          <span className="metric-label"><ClockCircleOutlined />超时任务</span>
          <strong>{data.overdueTasks.length}</strong>
          <small>已超过节点处理时限</small>
        </article>
        <article className="metric metric-primary">
          <span className="metric-label"><InboxOutlined />待投递事件</span>
          <strong>{data.outbox.pending}</strong>
          <small>{data.outbox.oldest_pending ? `最早 ${formatTime(data.outbox.oldest_pending)}` : '当前没有消息积压'}</small>
        </article>
        <article className="metric metric-danger">
          <span className="metric-label"><WarningOutlined />失败事件</span>
          <strong>{data.outbox.dead}</strong>
          <small>达到最大重试次数</small>
        </article>
      </section>

      <div className="workplace-main-grid">
        <section className="workplace-section">
          <div className="section-heading"><div><p className="section-kicker">BLOCKED FLOW</p><h2>卡死实例</h2></div><span className="section-count">{data.stuckInstances.length} 个</span></div>
          <div className="table-scroll">
            <Table<StuckInstance>
              rowKey="id"
              size="middle"
              pagination={false}
              dataSource={data.stuckInstances}
              locale={{ emptyText: empty('没有发现卡死实例') }}
              columns={[
                { title: '实例', dataIndex: 'id', width: 90, render: (id: number) => <span className="mono-cell">#{id}</span> },
                { title: '当前节点', dataIndex: 'current_node_id', ellipsis: true, render: (value?: string) => value || '未记录' },
                { title: '发起时间', dataIndex: 'started_at', width: 150, render: formatTime },
              ]}
            />
          </div>
        </section>

        <section className="workplace-section">
          <div className="section-heading"><div><p className="section-kicker">OVERDUE QUEUE</p><h2>超时任务</h2></div><span className="section-count">{data.overdueTasks.length} 项</span></div>
          <div className="table-scroll">
            <Table<OverdueTask>
              rowKey="task_id"
              size="middle"
              pagination={false}
              dataSource={data.overdueTasks}
              locale={{ emptyText: empty('当前没有超时任务') }}
              columns={[
                { title: '任务', dataIndex: 'task_id', width: 80, render: (id: number) => <span className="mono-cell">#{id}</span> },
                { title: '实例', dataIndex: 'instance_id', width: 80 },
                { title: '节点', dataIndex: 'node_id', ellipsis: true },
                { title: '处理人', dataIndex: 'assignee_id', width: 80 },
                { title: '超时时间', dataIndex: 'timeout_at', width: 150, render: formatTime },
              ]}
              scroll={{ x: 620 }}
            />
          </div>
        </section>
      </div>

      <section className="workplace-section" style={{ maxWidth: 1500, margin: '0 auto' }}>
        <div className="section-heading"><div><p className="section-kicker">NODE QUALITY</p><h2>节点驳回率</h2></div><span className="section-count">按已处理任务统计</span></div>
        <div className="table-scroll">
          <Table<RejectionRate>
            rowKey="node_id"
            size="middle"
            pagination={false}
            dataSource={data.nodeRejectionRates}
            locale={{ emptyText: empty('暂无已处理节点数据') }}
            columns={[
              { title: '节点', dataIndex: 'node_id', ellipsis: true },
              { title: '已处理', dataIndex: 'decided', width: 110 },
              { title: '驳回', dataIndex: 'rejected', width: 110 },
              { title: '驳回率', dataIndex: 'reject_rate', width: 130, render: (value?: number) => <Tag color={Number(value) >= 30 ? 'red' : 'blue'}>{Number(value ?? 0).toFixed(2)}%</Tag> },
            ]}
          />
        </div>
      </section>
    </div>
  );
}

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
}
