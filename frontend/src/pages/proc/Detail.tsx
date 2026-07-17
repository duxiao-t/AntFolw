import {
  Card,
  Descriptions,
  Timeline,
  Tag,
  Spin,
  Space,
  Button,
  Modal,
  Input,
  Select,
  message,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams, history, request, useModel } from '@umijs/max';
import { useMemo, useState } from 'react';

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

// 从 process_snapshot 里 DFS 出所有可作为驳回目标的 APPROVAL 节点
function findApproverNodes(node: any, acc: any[] = []): any[] {
  if (!node) return acc;
  if (node.type === 'APPROVAL') {
    acc.push({ id: node.id, name: node.name ?? node.id });
  }
  if (node.children) findApproverNodes(node.children, acc);
  if (Array.isArray(node.branchs)) {
    for (const b of node.branchs) findApproverNodes(b, acc);
  }
  return acc;
}

export default function DetailPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const { initialState } = useModel('@@initialState');
  const currentUserId = (initialState?.currentUser as any)?.id;

  const { data, isFetching } = useQuery({
    queryKey: ['instance', id],
    queryFn: () => request(`/api/instances/${id}`),
  });

  const [rejectFor, setRejectFor] = useState<
    { taskId: number; targetNodeId: string | null } | null
  >(null);
  const [rejectComment, setRejectComment] = useState('');
  const [withdrawOpen, setWithdrawOpen] = useState(false);

  const snapshotObj = useMemo(() => {
    const raw = (data as any)?.instance?.processSnapshot;
    if (!raw) return null;
    try {
      return typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch {
      return null;
    }
  }, [data]);

  if (isFetching || !data) return <Spin />;
  const { instance, tasks, history: historyRows } = data as any;
  if (!instance) return <Spin />;

  const ccTasks = (tasks ?? []).filter((t: any) => t.status === 'CC');
  const normalTasks = (tasks ?? []).filter((t: any) => t.status !== 'CC');
  const myPending = (tasks ?? []).find(
    (t: any) =>
      t.status === 'PENDING' &&
      currentUserId != null &&
      t.assigneeId === currentUserId,
  );
  const isStarter =
    currentUserId != null && instance.startedBy === currentUserId;
  const isRunner = instance.status === 'RUNNING';
  const rejectTargets = snapshotObj ? findApproverNodes(snapshotObj) : [];

  async function doApprove(taskId: number) {
    try {
      await request(`/api/tasks/${taskId}/approve`, {
        method: 'POST',
        data: {},
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

  return (
    <Card
      title={`流程实例 #${instance.id}`}
      extra={
        <Space>
          {myPending && isRunner && (
            <>
              <Button
                type="primary"
                onClick={() => doApprove(myPending.id)}
              >
                同意
              </Button>
              <Button
                danger
                onClick={() =>
                  setRejectFor({ taskId: myPending.id, targetNodeId: null })
                }
              >
                驳回
              </Button>
            </>
          )}
          {isStarter && isRunner && (
            <Button onClick={() => setWithdrawOpen(true)}>撤回流程</Button>
          )}
          <Button onClick={() => history.back()}>返回</Button>
        </Space>
      }
    >
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

      <Modal
        title="驳回"
        open={!!rejectFor}
        onCancel={() => setRejectFor(null)}
        onOk={doReject}
        okText="确定驳回"
        okButtonProps={{ danger: true }}
      >
        <div style={{ marginBottom: 12 }}>
          <label>驳回到</label>
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
          <label>意见</label>
          <Input.TextArea
            rows={3}
            value={rejectComment}
            onChange={(e) => setRejectComment(e.target.value)}
            placeholder="请说明驳回原因"
          />
        </div>
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