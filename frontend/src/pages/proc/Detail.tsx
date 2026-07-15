import { Card, Descriptions, Timeline, Tag, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useParams, history } from '@umijs/max';
import { Button } from 'antd';
import { request } from '@umijs/max';

export default function DetailPage() {
  const { id } = useParams();
  const { data, isFetching } = useQuery({
    queryKey: ['instance', id],
    queryFn: () => request(`/api/instances/${id}`),
  });
  if (isFetching || !data) return <Spin />;
  const { instance, tasks, history } = data;

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
        items={(tasks ?? []).map((t: any) => ({
          color:
            t.status === 'APPROVED' ? 'green'
              : t.status === 'REJECTED' ? 'red'
                : t.status === 'SKIPPED' ? 'gray'
                  : 'blue',
          children: (
            <div>
              <strong>{t.nodeId}</strong>
              {' · '}
              <Tag>{t.status}</Tag>
              {' · assignee='}
              {t.assigneeId}
              {t.comment ? ` · "${t.comment}"` : ''}
            </div>
          ),
        }))}
      />

      <h3 style={{ marginTop: 24 }}>历史</h3>
      <Timeline
        items={(history ?? []).map((h: any) => ({
          children: (
            <div>
              <strong>{h.action}</strong>
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
