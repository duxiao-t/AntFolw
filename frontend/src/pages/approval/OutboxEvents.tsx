import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, useAccess } from '@umijs/max';
import { Button, Result, Table } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';

export default function OutboxEvents() {
  const [page, setPage] = useState(1);
  const access = useAccess();
  const client = useQueryClient();
  const events = useQuery<AntFlowAPI.OutboxPage>({
    queryKey: ['workflow-outbox', page],
    queryFn: () => request(`/api/workflow-monitor/outbox?status=DEAD&page=${page}&size=20`),
    refetchInterval: 30_000,
  });
  const retry = useMutation({
    mutationFn: (id: string) => request(`/api/workflow-monitor/outbox/${id}/retry`, { method: 'POST' }),
    onSuccess: async () => {
      await Promise.allSettled([
        client.invalidateQueries({ queryKey: ['workflow-outbox'] }),
        client.invalidateQueries({ queryKey: ['workflow-monitor'] }),
      ]);
    },
  });
  const canRetry = typeof access.can === 'function' && access.can('workflow:outbox:retry');

  return (
    <section className="workplace-section" style={{ maxWidth: 1500, margin: '0 auto' }}>
      <div className="section-heading">
        <div><p className="section-kicker">FAILED MESSAGES</p><h2>失败消息</h2></div>
        <Button onClick={() => events.refetch()} loading={events.isFetching}>刷新消息</Button>
      </div>
      {events.isError ? (
        <Result status="error" title="失败消息加载失败" extra={<Button onClick={() => events.refetch()}>重试加载</Button>} />
      ) : (
        <div className="table-scroll">
          <Table<AntFlowAPI.OutboxEvent>
            rowKey="id"
            loading={events.isLoading}
            dataSource={events.data?.records ?? []}
            locale={{ emptyText: '当前没有失败消息' }}
            pagination={{ current: page, pageSize: 20, total: events.data?.total ?? 0,
              showSizeChanger: false, onChange: setPage }}
            columns={[
              { title: '实例', dataIndex: 'instanceId', width: 90 },
              { title: '事件', dataIndex: 'eventType', ellipsis: true },
              { title: '尝试次数', dataIndex: 'attempts', width: 100 },
              { title: '失败原因', dataIndex: 'error', ellipsis: true },
              { title: '创建时间', dataIndex: 'createdAt', width: 150,
                render: (value?: string) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—' },
              ...(canRetry ? [{
                title: '操作', key: 'actions', width: 100,
                render: (_: unknown, row: AntFlowAPI.OutboxEvent) => (
                  <Button disabled={!row.id || retry.isPending} onClick={() => row.id && retry.mutate(row.id)}>
                    重新投递
                  </Button>
                ),
              }] : []),
            ]}
          />
        </div>
      )}
    </section>
  );
}
