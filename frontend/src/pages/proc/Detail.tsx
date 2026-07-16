import { Card, Descriptions, Timeline, Tag, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useParams, history } from '@umijs/max';
import { Button } from 'antd';
import { request } from '@umijs/max';

const ACTION_LABEL: Record<string, string> = {
  START: '发起',
  ARRIVE: '到达',
  APPROVE: '同意',
  REJECT: '驳回',
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

export default function DetailPage() {
  const { id } = useParams();
  const { data, isFetching } = useQuery({
    queryKey: ['instance', id],
    queryFn: () => request(`/api/instances/${id}`),
  });
  if (isFetching || !data) return <Spin />;
  const { instance, tasks, history } = data;

  const ccTasks = (tasks ?? []).filter((t: any) => t.status === 'CC');
  const normalTasks = (tasks ?? []).filter((t: any) => t.status !== 'CC');

  return (
    <Card
      title={`流程实例 #${instance.id}`}
      extra={<Button onClick={() => history.back()}>返回</Button>}
    >
      <Descriptions bordered size="small" column={2}>
        <Descriptions.Item label="ID">{instance.id}</Descriptions.Item>
        <Descriptions.Item label="状态"><Tag>{instance.status}</Tag></Descriptions.Item>
        <Descriptions.Item label="流程">{instance.procDefId}</Descriptions.Item>
        <Descriptions.Item label="发起人">{instance.startedBy}</Descriptions.Item>
        <Descriptions.Item label="发起时间">{instance.startedAt}</Descriptions.Item>
        <Descriptions.Item label="完成时间">{instance.finishedAt ?? '—'}</Descriptions.Item>
      </Descriptions>

      <h3 style={{ marginTop: 24 }}>任务</h3>
      <Timeline
        items={normalTasks.map((t: any) => ({
          color:
            t.status === 'APPROVED' ? 'green'
              : t.status === 'REJECTED' ? 'red'
                : t.status === 'SKIPPED' ? 'gray'
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
        items={(history ?? []).map((h: any) => ({
          children: (
            <div>
              <strong>{actionLabel(h.action)}</strong>
              {h.fromNodeId ? ` · ${h.fromNodeId} → ${h.toNodeId ?? 'end'}` : ''}
              {' · '}
              {h.operatorId ? `operator=${h.operatorId}` : 'system'}
              {h.comment ? ` · "${h.comment}"` : ''}
              {' · '}
              <small>{h.createdAt}</small>
            </div>
          ),
        }))}
      />
    </Card>
  );
}