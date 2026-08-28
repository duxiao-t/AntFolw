import {
  App,
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Timeline,
} from 'antd';
import { RedoOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams, history, request, useModel } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import { FormRenderer } from '../../components/FormRenderer/FormRenderer';
import type { FieldMode } from '../../registry/types';
import { pickEditableValues } from './fieldPermissions';

const ACTION_LABEL: Record<string, string> = {
  START: '发起',
  ARRIVE: '到达',
  APPROVE: '同意',
  REJECT: '驳回',
  REJECT_TO_NODE: '驳回到节点',
  SKIP: '跳过',
  WITHDRAW: '撤回',
  COMPLETE: '完成',
  CC: '抄送',
  AUTO_PASS: '自动通过',
  DELAY_SCHEDULED: '延时已计划',
  DELAY_COMPLETED: '延时已完成',
  TRIGGER_QUEUED: '触发器已入队',
  TRIGGER_SUCCEEDED: '触发器发送成功',
  TRIGGER_FAILED: '触发器发送失败',
  FORCE_APPROVE: '紧急同意',
  FORCE_REJECT: '紧急驳回',
};

function actionLabel(action: string): string {
  return ACTION_LABEL[action] ?? action;
}

function statusTagColor(status: string): string {
  switch (status) {
    case 'APPROVED':
      return 'green';
    case 'REJECTED':
      return 'red';
    case 'SKIPPED':
      return 'gray';
    case 'CC':
      return 'cyan';
    case 'PENDING':
      return 'blue';
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
  const { message } = App.useApp();
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

  const { data, isFetching } = useQuery({
    queryKey: ['instance', id],
    queryFn: () => request(`/api/instances/${id}`),
  });

  const [rejectFor, setRejectFor] = useState<
    { taskId: number; targetNodeId: string | null } | null
  >(null);
  const [rejectComment, setRejectComment] = useState('');
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideAction, setOverrideAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [overrideTicket, setOverrideTicket] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [overrideTarget, setOverrideTarget] = useState<string | undefined>();
  const [editableValues, setEditableValues] = useState<Record<string, any>>({});

  const snapshotObj = useMemo(() => {
    const raw = (data as any)?.instance?.processSnapshot;
    if (!raw) return null;
    try {
      return typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch {
      return null;
    }
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

  if (isFetching || !data) return <Spin />;
  const { instance, tasks, history: historyRows } = data as any;
  if (!instance) return <Spin />;

  const ccTasks = (tasks ?? []).filter((t: any) => t.status === 'CC');
  const normalTasks = (tasks ?? []).filter((t: any) => t.status !== 'CC');
  const isStarter =
    currentUserId != null && instance.startedBy === currentUserId;
  const isRunner = instance.status === 'RUNNING';
  const rejectTargets = snapshotObj ? findApproverNodes(snapshotObj) : [];
  const pendingTask = (tasks ?? []).find((task: any) => task.status === 'PENDING' && task.taskType !== 'REWORK');
  const fullVisibility = (data as any).visibility !== 'SUMMARY';

  async function doApprove(taskId: number) {
    try {
      await request(`/api/tasks/${taskId}/approve`, {
        method: 'POST',
        data: hasEditableFields
          ? pickEditableValues(editableValues, currentFormModes)
          : {},
      });
      message.success('已同意');
      qc.invalidateQueries({ queryKey: ['instance', id] });
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  }

  async function doReject() {
    if (!rejectFor) return;
    try {
      await request(`/api/tasks/${rejectFor.taskId}/reject`, {
        method: 'POST',
        data: {
          comment: rejectComment,
          ...(rejectFor.targetNodeId
            ? { rejectToNodeId: rejectFor.targetNodeId }
            : {}),
        },
      });
      message.success(
        rejectFor.targetNodeId
          ? `已驳回到 ${rejectFor.targetNodeId}`
          : '已驳回，流程结束',
      );
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
      message.success('已撤回');
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
      Modal.confirm({
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

  return (
    <Card
      title={`流程实例 #${instance.id}`}
      extra={
        <Space>
          {myPending && isRunner && fullVisibility && (
            <>
              {canApprove && (
              <Button
                type="primary"
                onClick={() => doApprove(myPending.id)}
              >
                同意
              </Button>
              )}
              {canReject && (
              <Button
                danger
                onClick={() =>
                  setRejectFor({ taskId: myPending.id, targetNodeId: null })
                }
              >
                驳回
              </Button>
              )}
            </>
          )}
          {canWithdraw && isStarter && isRunner && fullVisibility && (
            <Button onClick={() => setWithdrawOpen(true)}>撤回流程</Button>
          )}
          {canOverride && isRunner && pendingTask && fullVisibility && (
            <Button danger icon={<ThunderboltOutlined />} onClick={() => setOverrideOpen(true)}>
              紧急介入
            </Button>
          )}
          <Button onClick={() => history.back()}>返回</Button>
        </Space>
      }
    >
      {!fullVisibility && (
        <Alert
          type="info"
          showIcon
          message="当前账号仅可查看流程摘要"
          description="表单内容、附件、自动化作业和流程操作已隐藏。"
          style={{ marginBottom: 16 }}
        />
      )}
      <Descriptions bordered size="small" column={2}>
        <Descriptions.Item label="ID">{instance.id}</Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag color={statusTagColor(instance.status)}>{instance.status}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="流程">
          v{instance.processDefVersion ?? '?'} · def#{instance.procDefId}
        </Descriptions.Item>
        <Descriptions.Item label="发起人">{instance.startedBy}</Descriptions.Item>
        <Descriptions.Item label="发起时间">{instance.startedAt}</Descriptions.Item>
        <Descriptions.Item label="完成时间">
          {instance.finishedAt ?? '—'}
        </Descriptions.Item>
      </Descriptions>

      {fullVisibility && formSchema.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <h3>表单详情</h3>
          <FormRenderer
            schema={formSchema}
            mode="readonly"
            fieldModes={currentFormModes}
            value={editableValues}
            onChange={hasEditableFields ? setEditableValues : undefined}
          />
        </div>
      )}

      {snapshotObj && (
        <div
          style={{
            marginTop: 12,
            padding: 12,
            background: '#fafafa',
            borderRadius: 6,
          }}
        >
          <strong>流程快照（v{instance.processDefVersion}）</strong>
          <div style={{ fontSize: 12, color: '#666', marginTop: 4 }}>
            {rejectTargets.length} 个审批节点：
            {rejectTargets.map((t: any) => t.name).join(' / ') || '无'}
          </div>
        </div>
      )}

      <h3 style={{ marginTop: 24 }}>任务</h3>
      <Timeline
        items={normalTasks.map((t: any) => ({
          color:
            t.status === 'APPROVED'
              ? 'green'
              : t.status === 'REJECTED'
                ? 'red'
                : t.status === 'SKIPPED'
                  ? 'gray'
                  : 'blue',
          children: (
            <div>
              <strong>{t.nodeId}</strong>
              {' · '}
              <Tag color={statusTagColor(t.status)}>{t.status}</Tag>
              {' · assignee='}
              {t.assigneeId}
              {t.comment ? ` · "${t.comment}"` : ''}
            </div>
          ),
        }))}
      />

      <h3 style={{ marginTop: 24 }}>抄送人</h3>
      {ccTasks.length === 0 ? (
        <div style={{ color: '#999' }}>无抄送任务</div>
      ) : (
        <Timeline
          items={ccTasks.map((t: any) => ({
            color: 'cyan',
            children: (
              <div>
                <strong>{t.nodeId}</strong>
                {' · '}
                <Tag color={statusTagColor('CC')}>CC</Tag>
                {' · assignee='}
                {t.assigneeId}
                {t.comment ? ` · "${t.comment}"` : ''}
              </div>
            ),
          }))}
        />
      )}

      <h3 style={{ marginTop: 24 }}>历史</h3>
      <Timeline
        items={(historyRows ?? []).map((h: any) => ({
          children: (
            <div>
              <strong>{actionLabel(h.action)}</strong>
              {h.fromNodeId
                ? ` · ${h.fromNodeId} → ${h.toNodeId ?? 'end'}`
                : ''}
              {' · '}
              {h.operatorId ? `operator=${h.operatorId}` : 'system'}
              {h.comment ? ` · "${h.comment}"` : ''}
              {' · '}
              <small>{h.createdAt}</small>
            </div>
          ),
        }))}
      />

      {fullVisibility && (data as any).automationJobs?.length > 0 && (
        <>
          <h3 style={{ marginTop: 24 }}>自动化作业</h3>
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={(data as any).automationJobs}
            scroll={{ x: 760 }}
            columns={[
              {
                title: '节点',
                dataIndex: 'nodeId',
                minWidth: 140,
                render: (value: string, row: any) => (
                  <Space size={6}>
                    <strong>{value}</strong>
                    <Tag>{row.jobType === 'DELAY' ? '延时' : 'Webhook'}</Tag>
                  </Space>
                ),
              },
              {
                title: '状态',
                dataIndex: 'status',
                width: 110,
                render: (value: string) => (
                  <Tag color={statusTagColor(value)}>{value}</Tag>
                ),
              },
              {
                title: '执行',
                width: 150,
                render: (_: unknown, row: any) =>
                  `${row.attempts}/${row.maxAttempts} · ${row.blocking ? '成功后继续' : '发送后继续'}`,
              },
              {
                title: '计划时间',
                dataIndex: 'scheduledAt',
                width: 190,
              },
              {
                title: '失败原因',
                dataIndex: 'lastError',
                minWidth: 180,
                render: (value: string | null) => value ?? '—',
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
        </>
      )}

      <Modal
        title="驳回"
        open={!!rejectFor}
        onCancel={() => setRejectFor(null)}
        onOk={doReject}
        okText="确定驳回"
        okButtonProps={{ danger: true }}
      >
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
        <div>
          <div>意见</div>
          <Input.TextArea
            rows={3}
            value={rejectComment}
            onChange={(e) => setRejectComment(e.target.value)}
            placeholder="请说明驳回原因"
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
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
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
          撤回后所有 PENDING 任务将被标记为 SKIPPED，实例状态变为
          WITHDRAWN。此操作不可恢复。
        </p>
        <p style={{ color: '#999', fontSize: 12 }}>
          注意：如果已有任何任务被审批或驳回，无法撤回（引擎会返回
          ALREADY_ACTED）。
        </p>
      </Modal>
    </Card>
  );
}
