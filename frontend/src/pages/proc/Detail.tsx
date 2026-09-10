import {
  App,
  Alert,
  Button,
  Empty,
  Input,
  Modal,
  Result,
  Segmented,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  ArrowLeftOutlined,
  RedoOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams, history, request, useModel } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import { FormRenderer } from '../../components/FormRenderer/FormRenderer';
import {
  ApprovalCommentEditor,
  fetchApprovalCommentPresets,
} from '../../components/ApprovalCommentEditor';
import type { FieldMode } from '../../registry/types';
import {
  buildActivityItems,
  buildProgressStages,
  formatDateTime,
  formatDuration,
  type HistoryView,
  instanceStatus,
  type ActivityCategory,
  type ActivityItem,
  type InstancePresentation,
  nodeLabel,
  type PersonView,
  parseProcessSnapshot,
  type ProgressStage,
} from './detailPresentation';
import { pickEditableValues } from './fieldPermissions';
import './Detail.less';

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

// 并行分支节点需要 parallelId/branchId 上下文，不能直接作为重建入口。
function findApproverNodes(
  node: any,
  acc: any[] = [],
  insideParallelBranch = false,
): any[] {
  if (!node) return acc;
  if (node.type === 'APPROVAL' && !insideParallelBranch) {
    acc.push({ id: node.id, name: node.name ?? node.id });
  }
  if (node.children) {
    findApproverNodes(node.children, acc, insideParallelBranch);
  }
  if (Array.isArray(node.branchs)) {
    const branchContext = insideParallelBranch || node.type === 'PARALLEL';
    for (const branch of node.branchs) {
      findApproverNodes(branch, acc, branchContext);
    }
  }
  return acc;
}

function findNodeById(node: any, id: string): any {
  if (!node || typeof node !== 'object' || !node.id) return null;
  if (node.id === id) return node;
  if (Array.isArray(node.branchs)) {
    for (const branch of node.branchs) {
      const hit = findNodeById(branch, id);
      if (hit) return hit;
    }
  }
  return findNodeById(node.children, id);
}

export default function DetailPage() {
  const { id } = useParams();
  const { message, modal } = App.useApp();
  const qc = useQueryClient();
  const { initialState } = useModel('@@initialState');
  const currentUserId = (initialState?.currentUser as any)?.id;
  const roles = (initialState?.currentUser as any)?.roles ?? [];
  const permissions = (initialState?.currentUser as any)?.permissions ?? [];
  const isAdmin = roles.includes('admin');
  const canOverride = isAdmin || permissions.includes('workflow.instance.override');
  const canRetryAutomation = isAdmin || permissions.includes('workflow.automation.retry');
  const canApprove = isAdmin || permissions.includes('workflow.task.approve');
  const canReject = isAdmin || permissions.includes('workflow.task.reject');
  const canWithdraw = isAdmin || permissions.includes('workflow.instance.withdraw');

  const detailQuery = useQuery<{
    runtime: Record<string, any>;
    presentation: InstancePresentation;
  }>({
    queryKey: ['instance', id],
    queryFn: async () => {
      const [runtime, presentation] = await Promise.all([
        request<Record<string, any>>(`/api/instances/${id}`),
        request<InstancePresentation>(`/api/mobile/instances/${id}`),
      ]);
      return { runtime, presentation };
    },
  });
  const data = detailQuery.data?.runtime;
  const presentation = detailQuery.data?.presentation;

  const [rejectFor, setRejectFor] = useState<
    { taskId: number; targetNodeId: string | null } | null
  >(null);
  const [rejectComment, setRejectComment] = useState('');
  const [approveOpen, setApproveOpen] = useState(false);
  const [approveComment, setApproveComment] = useState('');
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideAction, setOverrideAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [overrideTicket, setOverrideTicket] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [overrideTarget, setOverrideTarget] = useState<string | undefined>();
  const [editableValues, setEditableValues] = useState<Record<string, any>>({});
  const [activityFilter, setActivityFilter] = useState<'all' | ActivityCategory>('all');

  const snapshotObj = useMemo(() => {
    const raw = (data as any)?.instance?.processSnapshot;
    return parseProcessSnapshot(raw);
  }, [data]);

  const formSchema = useMemo(() => {
    const raw = (data as any)?.schema;
    if (!raw) return [];
    try {
      return typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch {
      return [];
    }
  }, [data]);

  const initialFormData = useMemo(() => {
    const raw = (data as any)?.formData;
    if (!raw) return {};
    try {
      return typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch {
      return {};
    }
  }, [data]);

  const myPending = (data as any)?.tasks?.find(
    (t: any) =>
      t.status === 'PENDING' &&
      t.taskType !== 'REWORK' &&
      currentUserId != null &&
      t.assigneeId === currentUserId,
  );
  useEffect(() => {
    if (myPending) {
      setEditableValues(initialFormData);
    }
    // 每个待办任务只初始化一次，重新拉取详情后字段值以服务端为准。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myPending?.id]);
  const currentFormModes = useMemo(() => {
    const modes: Record<string, FieldMode> = {};
    if (!snapshotObj || !myPending) return modes;
    const node = findNodeById(snapshotObj, myPending.nodeId);
    for (const entry of node?.props?.formPerms ?? []) {
      if (entry.mode === 'HIDDEN') modes[entry.fieldId] = 'hidden';
      else if (entry.mode === 'EDITABLE') modes[entry.fieldId] = 'runtime-fill';
      else modes[entry.fieldId] = 'readonly';
    }
    return modes;
  }, [snapshotObj, myPending]);
  const hasEditableFields = Object.values(currentFormModes).includes('runtime-fill');
  const presetTaskId = approveOpen ? myPending?.id : rejectFor?.taskId;
  const presetsQuery = useQuery({
    queryKey: ['task-comment-presets', presetTaskId],
    queryFn: () => {
      if (presetTaskId == null) throw new Error('请选择审批任务');
      return fetchApprovalCommentPresets(presetTaskId);
    },
    enabled: presetTaskId != null,
    retry: 0,
  });

  const instance = (data as any)?.instance;
  const tasks = (data as any)?.tasks ?? [];
  const historyRows: HistoryView[] = presentation?.history ?? (data as any)?.history ?? [];
  const fullVisibility = data?.visibility !== 'SUMMARY';
  const actorIds = useMemo(
    () => fullVisibility
      ? [...new Set(historyRows.flatMap((row) => row.operatorId == null ? [] : [row.operatorId]))]
          .sort((left, right) => left - right)
      : [],
    [fullVisibility, historyRows],
  );
  const actorsQuery = useQuery<Record<number, PersonView>>({
    queryKey: ['instance-actors', id, actorIds.join(',')],
    queryFn: async () => {
      const people = await Promise.all(actorIds.map(async (actorId) => {
        try {
          return await request<PersonView>(`/api/mobile/users/${actorId}`, {
            skipErrorHandler: true,
          });
        } catch {
          return { id: actorId } satisfies PersonView;
        }
      }));
      return Object.fromEntries(people.map((person) => [person.id, person]));
    },
    enabled: actorIds.length > 0,
    retry: 0,
  });
  const progressStages = useMemo(
    () => buildProgressStages({
      root: snapshotObj,
      nodeInstances: (data as any)?.nodeInstances ?? [],
      approvalRecords: presentation?.approvalRecords ?? [],
      instanceStatus: instance?.status ?? '',
      currentNodeId: instance?.currentNodeId,
      currentNodeName: presentation?.currentNodeName,
    }),
    [data, instance?.currentNodeId, instance?.status, presentation?.approvalRecords,
      presentation?.currentNodeName, snapshotObj],
  );
  const activityItems = useMemo(
    () => buildActivityItems({
      root: snapshotObj,
      approvalRecords: presentation?.approvalRecords ?? [],
      history: historyRows,
      people: actorsQuery.data,
      applicantName: presentation?.applicantName,
    }),
    [actorsQuery.data, historyRows, presentation?.applicantName,
      presentation?.approvalRecords, snapshotObj],
  );
  const filteredActivities = activityFilter === 'all'
    ? activityItems : activityItems.filter((item) => item.category === activityFilter);

  if (detailQuery.isLoading) {
    return <ProcessDetailLoading />;
  }
  if (detailQuery.isError || !data || !instance || !presentation) {
    return (
      <div className="process-detail-page">
        <Result
          status="error"
          title="流程详情加载失败"
          subTitle="请检查网络连接后重新加载。"
          extra={<Button type="primary" onClick={() => detailQuery.refetch()}>重新加载</Button>}
        />
      </div>
    );
  }

  const isStarter =
    currentUserId != null && instance.startedBy === currentUserId;
  const isRunner = instance.status === 'RUNNING';
  const isRework = instance.currentNodeId === '__rework__';
  const rejectTargets = snapshotObj ? findApproverNodes(snapshotObj) : [];
  const pendingTask = (tasks ?? []).find((task: any) => task.status === 'PENDING' && task.taskType !== 'REWORK');
  const rejectTask = rejectFor
    ? (tasks ?? []).find((task: any) => task.id === rejectFor.taskId)
    : undefined;
  const fixedRejectTarget = ['AND', 'ALL', 'RATIO'].includes(
    rejectTask?.approvalMode,
  );

  async function doApprove(taskId: number) {
    try {
      await request(`/api/tasks/${taskId}/approve`, {
        method: 'POST',
        data: {
          comment: approveComment.trim() || undefined,
          ...(hasEditableFields
            ? {
                data: pickEditableValues(
                  editableValues,
                  currentFormModes,
                  formSchema,
                ),
              }
            : {}),
        },
      });
      message.success('已同意');
      setApproveOpen(false);
      setApproveComment('');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  }

  async function doReject() {
    if (!rejectFor) return;
    const comment = rejectComment.trim();
    if (!comment) {
      message.error('请填写驳回原因');
      return;
    }
    try {
      await request(`/api/tasks/${rejectFor.taskId}/reject`, {
        method: 'POST',
        data: {
          comment,
          ...(!fixedRejectTarget && rejectFor.targetNodeId
            ? { rejectToNodeId: rejectFor.targetNodeId }
            : {}),
        },
      });
      message.success('已提交驳回意见');
      setRejectFor(null);
      setRejectComment('');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  }

  async function doWithdraw() {
    try {
      await request(`/api/instances/${id}/withdraw`, { method: 'POST' });
      message.success('已撤回，流程已进入待修改');
      setWithdrawOpen(false);
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '撤回失败');
    }
  }

  async function retryAutomationJob(jobId: number) {
    try {
      await request(`/api/instances/${id}/jobs/${jobId}/retry`, {
        method: 'POST',
      });
      message.success('自动化作业已重新入队');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (error: any) {
      message.error(error?.message ?? '重试失败');
    }
  }

  async function submitOverride() {
    if (!pendingTask || !overrideTicket.trim() || !overrideReason.trim()) {
      message.error('请填写工单号和介入原因');
      return;
    }
    const execute = async () => {
      await request(`/api/tasks/${pendingTask.id}/override`, {
        method: 'POST',
        data: {
          action: overrideAction,
          ticketNo: overrideTicket.trim(),
          reason: overrideReason.trim(),
          rejectToNodeId: overrideAction === 'REJECT' ? overrideTarget : undefined,
        },
      });
      message.success(overrideAction === 'APPROVE' ? '已紧急同意' : '已紧急驳回');
      setOverrideOpen(false);
      setOverrideTicket('');
      setOverrideReason('');
      setOverrideTarget(undefined);
      qc.invalidateQueries({ queryKey: ['instance', id] });
    };
    if (isStarter) {
      modal.confirm({
        title: '介入本人发起的流程？',
        content: '该操作会记录为关键风险审计事件。',
        okText: '确认介入',
        okButtonProps: { danger: true },
        onOk: execute,
      });
      return;
    }
    await execute();
  }

  const statusView = instanceStatus(instance.status, instance.currentNodeId);
  const currentNodeName = isRework
    ? '等待发起人修改原单'
    : instance.status === 'APPROVED'
      ? '流程已完成'
      : instance.status === 'REJECTED'
        ? '流程已驳回'
        : presentation.currentNodeName || nodeLabel(snapshotObj, instance.currentNodeId);
  const applicantIdentity = [
    presentation.applicantDepartment,
    presentation.applicantEmployeeNo ? `工号 ${presentation.applicantEmployeeNo}` : null,
  ].filter(Boolean).join(' · ');
  const automationJobs = (data as any).automationJobs ?? [];
  const activityCounts = {
    approval: activityItems.filter((item) => item.category === 'approval').length,
    cc: activityItems.filter((item) => item.category === 'cc').length,
    system: activityItems.filter((item) => item.category === 'system').length,
  };

  return (
    <>
      <div className="process-detail-page">
        <div className="process-detail-shell">
          <header className="process-detail-hero">
            <div className="process-detail-hero__top">
              <div className="process-detail-identity">
                <Button
                  type="text"
                  className="process-detail-back"
                  icon={<ArrowLeftOutlined />}
                  aria-label="返回上一页"
                  onClick={() => history.back()}
                />
                <div>
                  <p className="process-detail-kicker">ANTFLOW / INSTANCE</p>
                  <div className="process-detail-title-row">
                    <h1>{presentation.formName || '未命名流程'}</h1>
                    <span className={`process-status process-status--${statusView.tone}`}>
                      {statusView.label}
                    </span>
                  </div>
                  <p className="process-detail-subtitle">
                    {presentation.businessNo ? `单号 ${presentation.businessNo}` : '尚未生成业务单号'}
                  </p>
                </div>
              </div>
              <div className="process-detail-actions">
                {myPending && isRunner && fullVisibility && (
                  <>
                    {canApprove && (
                      <Button type="primary" onClick={() => {
                        setApproveComment('');
                        setApproveOpen(true);
                      }}>同意</Button>
                    )}
                    {canReject && (
                      <Button
                        danger
                        disabled={!!myPending.parallelId}
                        title={myPending.parallelId ? '并行审批节点不允许驳回' : undefined}
                        onClick={() => setRejectFor({ taskId: myPending.id, targetNodeId: null })}
                      >
                        驳回
                      </Button>
                    )}
                  </>
                )}
                {canWithdraw && isStarter && isRunner && !isRework && fullVisibility && (
                  <Button onClick={() => setWithdrawOpen(true)}>撤回流程</Button>
                )}
                {canOverride && isRunner && pendingTask && fullVisibility && (
                  <Button danger icon={<ThunderboltOutlined />} onClick={() => setOverrideOpen(true)}>
                    紧急介入
                  </Button>
                )}
              </div>
            </div>

            <dl className="process-detail-meta">
              <div>
                <dt>当前进度</dt>
                <dd>{currentNodeName}</dd>
                <small>{statusView.label}</small>
              </div>
              <div>
                <dt>发起人</dt>
                <dd>{presentation.applicantName || `用户 #${instance.startedBy}`}</dd>
                <small>{applicantIdentity || '未记录部门与工号'}</small>
              </div>
              <div>
                <dt>发起时间</dt>
                <dd>{formatDateTime(instance.startedAt)}</dd>
                <small>以系统记录为准</small>
              </div>
              <div>
                <dt>{instance.finishedAt ? '完成时间' : '已运行'}</dt>
                <dd>{instance.finishedAt
                  ? formatDateTime(instance.finishedAt)
                  : formatDuration(instance.startedAt)}</dd>
                <small>{instance.finishedAt
                  ? `总耗时 ${formatDuration(instance.startedAt, instance.finishedAt)}`
                  : '流程仍在处理中'}</small>
              </div>
            </dl>

            {fullVisibility && (
              <details className="process-technical">
                <summary>技术信息 <span>仅用于排障</span></summary>
                <dl>
                  <TechnicalValue label="实例 ID" value={instance.id} />
                  <TechnicalValue label="流程定义" value={instance.procDefId} />
                  <TechnicalValue label="流程版本" value={instance.processDefVersion} />
                  <TechnicalValue label="当前节点 ID" value={instance.currentNodeId} />
                  <TechnicalValue label="引擎版本" value={instance.engineVersion} />
                </dl>
              </details>
            )}
          </header>

          {!fullVisibility && (
            <Alert
              type="info"
              showIcon
              title="当前账号仅可查看流程摘要"
              description="表单内容、附件、自动化作业和流程操作已隐藏。"
            />
          )}
          {fullVisibility && isRework && (
            <Alert
              type="info"
              showIcon
              title="流程待修改"
              description="请到移动端修改原表单并重新提交，原实例和单号已保留。"
            />
          )}

          <section className="process-detail-panel process-progress-panel" aria-labelledby="process-progress-title">
            <SectionHeading
              kicker="FLOW ROUTE"
              title="流程进度"
              id="process-progress-title"
              extra={`${progressStages.filter((stage) => stage.state === 'done').length}/${progressStages.length} 个阶段完成`}
            />
            <ProcessRail stages={progressStages} />
          </section>

          <div className={`process-detail-grid${fullVisibility ? '' : ' process-detail-grid--single'}`}>
            {fullVisibility && (
              <main className="process-detail-main">
                <section className="process-detail-panel form-detail-card" aria-labelledby="form-content-title">
                  <SectionHeading
                    kicker="FORM CONTENT"
                    title="表单内容"
                    id="form-content-title"
                    extra={hasEditableFields ? '当前审批节点可修改部分字段' : '提交时内容'}
                  />
                  <div className="form-detail-card__body">
                    {formSchema.length > 0 ? (
                      <FormRenderer
                        schema={formSchema}
                        mode="readonly"
                        fieldModes={currentFormModes}
                        value={myPending ? editableValues : initialFormData}
                        onChange={hasEditableFields ? setEditableValues : undefined}
                      />
                    ) : (
                      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可显示的表单内容" />
                    )}
                  </div>
                </section>

                {automationJobs.length > 0 && (
                  <section className="process-detail-panel" aria-labelledby="automation-title">
                    <SectionHeading
                      kicker="AUTOMATION"
                      title="自动化作业"
                      id="automation-title"
                      extra={`${automationJobs.length} 项`}
                    />
                    <div className="process-detail-table-scroll">
                      <Table
                        rowKey="id"
                        size="small"
                        pagination={false}
                        dataSource={automationJobs}
                        scroll={{ x: 760 }}
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
                            title: '失败原因',
                            dataIndex: 'lastError',
                            minWidth: 180,
                            render: (value: string | null) => value
                              ? <Typography.Text ellipsis={{ tooltip: value }}>{value}</Typography.Text>
                              : '—',
                          },
                          {
                            title: '操作',
                            width: 92,
                            fixed: 'right',
                            render: (_: unknown, row: any) =>
                              canRetryAutomation && row.status === 'FAILED' ? (
                                <Button
                                  size="small"
                                  icon={<RedoOutlined />}
                                  onClick={() => retryAutomationJob(row.id)}
                                >
                                  重试
                                </Button>
                              ) : null,
                          },
                        ]}
                      />
                    </div>
                  </section>
                )}
              </main>
            )}

            <aside className="process-detail-sidebar" aria-label="流程流转轨迹">
              <section className="process-detail-panel activity-panel">
                <SectionHeading
                  kicker="ACTIVITY"
                  title="流转轨迹"
                  id="activity-title"
                  extra={`${activityItems.length} 条记录`}
                />
                <div className="activity-filter">
                  <Segmented
                    block
                    size="small"
                    value={activityFilter}
                    onChange={(value) => setActivityFilter(value as 'all' | ActivityCategory)}
                    options={[
                      { label: `全部 ${activityItems.length}`, value: 'all' },
                      { label: `审批 ${activityCounts.approval}`, value: 'approval' },
                      { label: `抄送 ${activityCounts.cc}`, value: 'cc' },
                      { label: `系统 ${activityCounts.system}`, value: 'system' },
                    ]}
                  />
                </div>
                <ActivityList items={filteredActivities} />
              </section>
            </aside>
          </div>
        </div>
      </div>

      <Modal
        title="同意审批"
        open={approveOpen}
        onCancel={() => setApproveOpen(false)}
        onOk={() => myPending && doApprove(myPending.id)}
        okText="确认同意"
      >
        <ApprovalCommentEditor
          action="approve"
          presets={presetsQuery.data}
          value={approveComment}
          onChange={setApproveComment}
        />
      </Modal>

      <Modal
        title="驳回"
        open={!!rejectFor}
        onCancel={() => setRejectFor(null)}
        onOk={doReject}
        okText="确定驳回"
        okButtonProps={{ danger: true }}
      >
        {!fixedRejectTarget && (
          <div style={{ marginBottom: 12 }}>
            <div>驳回到</div>
            <Select
              style={{ width: '100%' }}
              value={rejectFor?.targetNodeId ?? '__END__'}
              onChange={(v) =>
                setRejectFor(
                  rejectFor
                    ? {
                        ...rejectFor,
                        targetNodeId: v === '__END__' ? null : v,
                      }
                    : null,
                )
              }
              options={[
                { value: '__END__', label: '结束流程（驳回=终止）' },
                ...rejectTargets.map((t: any) => ({
                  value: t.id,
                  label: `${t.name} (${t.id})`,
                })),
              ]}
            />
          </div>
        )}
        <div>
          <div>意见</div>
          <ApprovalCommentEditor
            action="reject"
            presets={presetsQuery.data}
            rows={3}
            value={rejectComment}
            onChange={setRejectComment}
          />
        </div>
      </Modal>

      <Modal
        title="紧急介入"
        open={overrideOpen}
        onCancel={() => setOverrideOpen(false)}
        onOk={submitOverride}
        okText="执行介入"
        okButtonProps={{ danger: true }}
      >
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <div>处理动作</div>
            <Select
              style={{ width: '100%' }}
              value={overrideAction}
              onChange={(value) => setOverrideAction(value)}
              options={[
                { value: 'APPROVE', label: '同意并继续流程' },
                { value: 'REJECT', label: '驳回' },
              ]}
            />
          </div>
          <div>
            <div>工单号</div>
            <Input value={overrideTicket} onChange={(event) => setOverrideTicket(event.target.value)} />
          </div>
          <div>
            <div>原因</div>
            <Input.TextArea rows={3} value={overrideReason} onChange={(event) => setOverrideReason(event.target.value)} />
          </div>
          {overrideAction === 'REJECT' && (
            <div>
              <div>驳回目标</div>
              <Select
                allowClear
                style={{ width: '100%' }}
                value={overrideTarget}
                onChange={setOverrideTarget}
                placeholder="默认退回上一级"
                options={rejectTargets.map((target: any) => ({ value: target.id, label: target.name }))}
              />
            </div>
          )}
        </Space>
      </Modal>

      <Modal
        title="确认撤回流程？"
        open={withdrawOpen}
        onCancel={() => setWithdrawOpen(false)}
        onOk={doWithdraw}
        okText="撤回"
        okButtonProps={{ danger: true }}
      >
        <p>
          撤回后流程将进入待修改，原实例、表单数据和单号都会保留，可在移动端修改后重新提交。
        </p>
        <p style={{ color: '#999', fontSize: 12 }}>
          当前审批轮次已有实际审批时不能撤回；已成功执行的外部 Webhook 无法回滚。
        </p>
      </Modal>
    </>
  );
}

function ProcessDetailLoading() {
  return (
    <div className="process-detail-page">
      <div className="process-detail-shell process-detail-loading">
        <Skeleton active avatar paragraph={{ rows: 4 }} />
        <Skeleton active paragraph={{ rows: 10 }} />
      </div>
    </div>
  );
}

function SectionHeading({
  kicker,
  title,
  id,
  extra,
}: {
  kicker: string;
  title: string;
  id: string;
  extra?: string;
}) {
  return (
    <header className="process-section-heading">
      <div>
        <p>{kicker}</p>
        <h2 id={id}>{title}</h2>
      </div>
      {extra ? <span>{extra}</span> : null}
    </header>
  );
}

function TechnicalValue({ label, value }: { label: string; value: unknown }) {
  const text = value == null || value === '' ? '—' : String(value);
  return (
    <div>
      <dt>{label}</dt>
      <dd>
        {text === '—' ? text : (
          <Typography.Text copyable={{ text, tooltips: ['复制', '已复制'] }}>
            {text}
          </Typography.Text>
        )}
      </dd>
    </div>
  );
}

function ProcessRail({ stages }: { stages: ProgressStage[] }) {
  return (
    <div className="process-rail-scroll">
      <ol className="process-rail">
        {stages.map((stage, index) => (
          <li
            key={stage.id}
            className={`process-stage process-stage--${stage.state}`}
            aria-current={stage.state === 'current' ? 'step' : undefined}
          >
            <div className="process-stage__track" aria-hidden="true">
              <span>{stage.state === 'done' ? '✓' : index + 1}</span>
            </div>
            <strong title={stage.label}>{stage.label}</strong>
            <small title={stage.detail}>{stage.detail}</small>
            <em>{progressStateLabel(stage.state)}</em>
          </li>
        ))}
      </ol>
    </div>
  );
}

function ActivityList({ items }: { items: ActivityItem[] }) {
  if (items.length === 0) {
    return (
      <div className="activity-empty">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前筛选下暂无流转记录" />
      </div>
    );
  }
  return (
    <ol className="activity-list">
      {items.map((item) => (
        <li key={item.id} className={`activity-item activity-item--${item.state}`}>
          <span className="activity-item__marker" aria-hidden="true">
            {activityMarker(item.category)}
          </span>
          <article>
            <div className="activity-item__head">
              <div>
                {item.context ? <span className="activity-item__context">{item.context}</span> : null}
                <h3>{item.title}</h3>
              </div>
              <span className="activity-item__status">{item.status}</span>
            </div>
            <div className="activity-item__person">
              <strong>{item.person}</strong>
              {item.identity ? <span>{item.identity}</span> : null}
              {item.source ? <span>{item.source}</span> : null}
            </div>
            <Typography.Paragraph
              className="activity-item__comment"
              ellipsis={{ rows: 2, expandable: 'collapsible', symbol: (expanded) => expanded ? '收起' : '展开' }}
            >
              {item.comment}
            </Typography.Paragraph>
            <footer>
              <span>{activityCategoryLabel(item.category)}</span>
              {item.roundNo > 1 ? <span>第 {item.roundNo} 次提交</span> : null}
              <time>{formatDateTime(item.time)}</time>
            </footer>
          </article>
        </li>
      ))}
    </ol>
  );
}

function progressStateLabel(state: ProgressStage['state']) {
  return ({ done: '已完成', current: '处理中', waiting: '待到达', error: '异常结束' } as const)[state];
}

function activityMarker(category: ActivityCategory) {
  return ({ approval: '审', cc: '抄', system: '系' } as const)[category];
}

function activityCategoryLabel(category: ActivityCategory) {
  return ({ approval: '审批记录', cc: '抄送记录', system: '系统记录' } as const)[category];
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
