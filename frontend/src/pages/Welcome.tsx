import {
  AuditOutlined,
  AlertOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  EyeOutlined,
  FileTextOutlined,
  InboxOutlined,
  LoadingOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, request, useModel, useNavigate } from '@umijs/max';
import { App, Button, Empty, Modal, Progress, Result, Skeleton, Space, Table, Tag } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import type { ReactNode } from 'react';
import './Welcome.css';
import {
  ApprovalCommentEditor,
  fetchApprovalCommentPresets,
} from '../components/ApprovalCommentEditor';

type Status = 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';

type PendingTaskItem = {
  taskId: number;
  instanceId: number;
  procInstId: number;
  formName: string;
  applicantId: number;
  applicantName: string;
  nodeId: string;
  status: string;
  parallelId?: string | null;
  createdAt?: string;
};

type RecentInstanceItem = {
  instanceId: number;
  formName: string;
  startedById: number;
  startedByName: string;
  status: Status;
  currentNodeId?: string;
  startedAt?: string;
  updatedAt?: string;
};

type WorkplaceOverview = {
  pendingTasks: number;
  runningInstances: number;
  completedToday: number;
  rejectedToday: number;
  pendingTaskItems: PendingTaskItem[];
  recentInstanceItems: RecentInstanceItem[];
  statusBreakdown: Record<string, number>;
};

type ActionState = { taskId: number; type: 'approve' | 'reject'; formName: string };

const statusMeta: Record<string, { label: string; color: string; icon: ReactNode }> = {
  RUNNING: { label: '运行中', color: '#0b57d0', icon: <LoadingOutlined /> },
  APPROVED: { label: '已完成', color: '#0f8a5f', icon: <CheckCircleOutlined /> },
  REJECTED: { label: '已驳回', color: '#c0392b', icon: <CloseCircleOutlined /> },
  WITHDRAWN: { label: '已撤回', color: '#5f6f80', icon: <ClockCircleOutlined /> },
};

const managementLinks = [
  { href: '/approval/forms', label: '表单管理', detail: '维护表单与流程配置', icon: <FileTextOutlined />, permission: 'page.approval.forms' },
  { href: '/approval/records', label: '审批记录', detail: '查询实例与审批轨迹', icon: <AuditOutlined />, permission: 'page.approval.records' },
  { href: '/approval/monitor', label: '流程监控', detail: '定位卡死、超时和消息积压', icon: <AlertOutlined />, permission: 'workflow.instance.override' },
  { href: '/org/contacts', label: '组织架构', detail: '管理部门与人员信息', icon: <TeamOutlined />, permission: 'page.org.contacts' },
  { href: '/security/roles', label: '权限与安全', detail: '角色、权限与审计入口', icon: <SafetyCertificateOutlined />, permission: 'page.security.roles' },
  { href: '/report/center', label: '报表中心', detail: '查看业务数据报表', icon: <BarChartOutlined />, permission: 'page.report.center' },
];

function formatDate(value?: string) {
  return value ? dayjs(value).format('MM-DD HH:mm') : '—';
}

function statusTag(status: string) {
  const meta = statusMeta[status] ?? { label: status, color: '#5f6f80', icon: null };
  return <Tag color={meta.color}>{meta.label}</Tag>;
}

function EmptyPanel({ text }: { text: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />;
}

export default function Workplace() {
  const { initialState } = useModel('@@initialState');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const user = initialState?.currentUser as
    | (API.CurrentUser & { permissions?: string[] })
    | undefined;
  const roles = user?.roles ?? [];
  const permissions = user?.permissions ?? [];
  const can = (permission: string) => roles.includes('admin') || permissions.includes(permission);
  const canApprove = roles.includes('admin') || permissions.includes('workflow.task.approve');
  const canReject = roles.includes('admin') || permissions.includes('workflow.task.reject');
  const displayName = user?.displayName ?? user?.name ?? user?.username ?? '当前用户';

  const { data, isLoading, isFetching, isError, refetch } = useQuery<WorkplaceOverview>({
    queryKey: ['workplace-overview'],
    queryFn: () => request<WorkplaceOverview>('/api/workplace/overview'),
  });
  const [action, setAction] = useState<ActionState | null>(null);
  const [comment, setComment] = useState('');
  const presetsQuery = useQuery({
    queryKey: ['task-comment-presets', action?.taskId],
    queryFn: () => {
      if (!action) throw new Error('请选择审批操作');
      return fetchApprovalCommentPresets(action.taskId);
    },
    enabled: action != null,
    retry: 0,
  });
  const actionMutation = useMutation({
    mutationFn: async () => {
      if (!action) throw new Error('请选择审批操作');
      return request(`/api/tasks/${action.taskId}/${action.type}`, {
        method: 'POST',
        data: { comment: comment.trim() || undefined },
      });
    },
    onSuccess: () => {
      message.success(action?.type === 'approve' ? '已同意审批' : '已驳回审批');
      setAction(null);
      setComment('');
      queryClient.invalidateQueries({ queryKey: ['workplace-overview'] });
    },
    onError: (error: any) => message.error(error?.message ?? '审批操作失败，请稍后重试'),
  });

  if (isLoading) {
    return <div className="workplace-page"><div className="workplace-loading"><Skeleton active paragraph={{ rows: 2 }} /><Skeleton active paragraph={{ rows: 8 }} /></div></div>;
  }

  if (isError || !data) {
    return (
      <div className="workplace-page">
        <Result
          status="error"
          title="工作台数据暂时不可用"
          subTitle="请检查网络连接，或稍后重新加载审批运营数据。"
          extra={<Button type="primary" icon={<ReloadOutlined />} onClick={() => refetch()}>重新加载</Button>}
        />
      </div>
    );
  }

  const totalStatuses = Object.values(data.statusBreakdown).reduce((sum, value) => sum + value, 0);
  const visibleManagementLinks = managementLinks.filter((item) => can(item.permission));

  return (
    <div className="workplace-page">
      <header className="workplace-header">
        <div>
          <p className="workplace-kicker">ANTFLOW / OPERATIONS DESK</p>
          <h1>审批运营中心</h1>
          <p className="workplace-subtitle">把待办、流程和异常集中在一个清晰的工作面上。</p>
        </div>
        <div className="workplace-header-actions">
          <Button icon={<ReloadOutlined spin={isFetching} />} onClick={() => refetch()} loading={isFetching}>刷新数据</Button>
          <div className="workplace-user"><span>{displayName.slice(0, 1)}</span><strong>{displayName}</strong></div>
        </div>
      </header>

      <section className="metric-grid" aria-label="运营指标">
        <article className="metric metric-primary"><span className="metric-label"><InboxOutlined />待审批任务</span><strong>{data.pendingTasks}</strong><small>当前账号待处理</small></article>
        <article className="metric metric-primary-deep"><span className="metric-label"><LoadingOutlined />运行中流程</span><strong>{data.runningInstances}</strong><small>授权范围内实例</small></article>
        <article className="metric metric-success"><span className="metric-label"><CheckCircleOutlined />今日已完成</span><strong>{data.completedToday}</strong><small>今天完成的流程</small></article>
        <article className="metric metric-danger"><span className="metric-label"><CloseCircleOutlined />今日驳回</span><strong>{data.rejectedToday}</strong><small>需要关注的结果</small></article>
      </section>

      <div className="workplace-main-grid">
        <section className="workplace-section pending-section" aria-labelledby="pending-title">
          <div className="section-heading"><div><p className="section-kicker">ACTION QUEUE</p><h2 id="pending-title">待处理审批</h2></div><span className="section-count">{data.pendingTasks} 项</span></div>
          <div className="table-scroll">
            <Table<PendingTaskItem>
              rowKey="taskId"
              size="middle"
              pagination={false}
              dataSource={data.pendingTaskItems}
              locale={{ emptyText: <EmptyPanel text="当前没有待处理审批" /> }}
              columns={[
                { title: '申请事项', dataIndex: 'formName', ellipsis: true, render: (value: string, item) => <div className="primary-cell"><strong>{value}</strong><small>#{item.instanceId}</small></div> },
                { title: '申请人', dataIndex: 'applicantName', width: 112 },
                { title: '当前节点', dataIndex: 'nodeId', width: 118, ellipsis: true },
                { title: '到达时间', dataIndex: 'createdAt', width: 124, render: (value: string) => formatDate(value) },
                { title: '操作', key: 'action', width: 170, fixed: 'right', render: (_, item) => <Space size={2}><Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/proc/${item.instanceId}`)}>查看</Button>{canApprove && <Button type="link" size="small" onClick={() => { setAction({ taskId: item.taskId, type: 'approve', formName: item.formName }); setComment(''); }}>审批</Button>}{canReject && <Button type="link" size="small" danger disabled={!!item.parallelId} title={item.parallelId ? '并行审批节点不允许驳回' : undefined} onClick={() => { setAction({ taskId: item.taskId, type: 'reject', formName: item.formName }); setComment(''); }}>驳回</Button>}</Space> },
              ]}
              scroll={{ x: 700 }}
            />
          </div>
        </section>

        <section className="workplace-section status-section" aria-labelledby="status-title">
          <div className="section-heading"><div><p className="section-kicker">FLOW HEALTH</p><h2 id="status-title">流程运行概况</h2></div><span className="section-count">{totalStatuses} 个实例</span></div>
          <div className="status-summary"><strong>{data.runningInstances}</strong><span>当前运行中</span></div>
          <div className="status-list">
            {Object.entries(statusMeta).map(([status, meta]) => {
              const count = data.statusBreakdown[status] ?? 0;
              const percent = totalStatuses ? Math.round((count / totalStatuses) * 100) : 0;
              return <div className="status-row" key={status}><div className="status-row-label"><span style={{ color: meta.color }}>{meta.icon}</span><span>{meta.label}</span><strong>{count}</strong></div><Progress percent={percent} showInfo={false} strokeColor={meta.color} railColor="#e6ebf3" /></div>;
            })}
          </div>
          {totalStatuses === 0 && <p className="inline-empty">授权范围内暂无流程实例</p>}
        </section>
      </div>

      <div className="workplace-bottom-grid">
        <section className="workplace-section recent-section" aria-labelledby="recent-title">
          <div className="section-heading"><div><p className="section-kicker">LATEST ACTIVITY</p><h2 id="recent-title">最近流程</h2></div><Link to="/approval/records">查看全部</Link></div>
          <div className="table-scroll">
            <Table<RecentInstanceItem>
              rowKey="instanceId"
              size="middle"
              pagination={false}
              dataSource={data.recentInstanceItems}
              locale={{ emptyText: <EmptyPanel text="暂无流程记录" /> }}
              onRow={(item) => ({ onClick: () => navigate(`/proc/${item.instanceId}`), className: 'clickable-row' })}
              columns={[
                { title: '实例', dataIndex: 'instanceId', width: 80, render: (value: number) => <span className="mono-cell">#{value}</span> },
                { title: '表单', dataIndex: 'formName', ellipsis: true, render: (value: string) => <strong>{value}</strong> },
                { title: '发起人', dataIndex: 'startedByName', width: 110 },
                { title: '状态', dataIndex: 'status', width: 100, render: (value: Status) => statusTag(value) },
                { title: '当前节点', dataIndex: 'currentNodeId', width: 118, ellipsis: true, render: (value: string, item) => value ?? (item.status === 'RUNNING' ? '处理中' : '流程结束') },
                { title: '更新时间', dataIndex: 'updatedAt', width: 124, render: (value: string) => formatDate(value) },
              ]}
              scroll={{ x: 650 }}
            />
          </div>
        </section>

        <section className="workplace-section management-section" aria-labelledby="management-title">
          <div className="section-heading"><div><p className="section-kicker">CONTROL ROOM</p><h2 id="management-title">管理入口</h2></div></div>
          {visibleManagementLinks.length ? <div className="management-list">{visibleManagementLinks.map((item) => <Link className="management-link" to={item.href} key={item.href}><span className="management-icon">{item.icon}</span><span><strong>{item.label}</strong><small>{item.detail}</small></span><span className="management-arrow">→</span></Link>)}</div> : <EmptyPanel text="当前账号暂无管理模块权限" />}
        </section>
      </div>

      <Modal
        open={!!action}
        title={action?.type === 'approve' ? `审批：${action.formName}` : `驳回：${action?.formName ?? ''}`}
        okText={action?.type === 'approve' ? '确认同意' : '确认驳回'}
        okButtonProps={action?.type === 'reject' ? { danger: true } : undefined}
        confirmLoading={actionMutation.isPending}
        onCancel={() => setAction(null)}
        onOk={() => {
          if (action?.type === 'reject' && !comment.trim()) {
            message.error('请填写驳回原因');
            return;
          }
          actionMutation.mutate();
        }}
      >
        <ApprovalCommentEditor
          action={action?.type ?? 'approve'}
          presets={presetsQuery.data}
          value={comment}
          onChange={setComment}
        />
      </Modal>
    </div>
  );
}
