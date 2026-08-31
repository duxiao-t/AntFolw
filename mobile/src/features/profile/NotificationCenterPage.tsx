import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { queryKeys } from '../../shared/api/queryKeys';
import { AppPage } from '../../shared/ui/AppPage';
import { PageEmpty, PageError, PageSkeleton } from '../../shared/ui/PageStates';
import {
  fetchNotifications,
  markNotificationRead,
  type UserNotification,
} from './notifications.api';

export function NotificationCenterPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const query = useInfiniteQuery({
    queryKey: queryKeys.notifications,
    queryFn: ({ pageParam }) => fetchNotifications(pageParam),
    initialPageParam: 1,
    getNextPageParam: (page, pages) => (page.hasMore ? pages.length + 1 : undefined),
  });
  const readMutation = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications });
      void queryClient.invalidateQueries({ queryKey: queryKeys.bootstrap });
    },
  });

  if (query.isPending) return <PageSkeleton rows={5} />;
  if (query.isError) {
    return <AppPage title="消息中心"><PageError title="消息加载失败" message="请检查网络后重新加载。" onRetry={() => void query.refetch()} /></AppPage>;
  }

  const notifications = query.data.pages.flatMap((page) => page.items);
  const unreadCount = query.data.pages[0]?.unreadCount ?? 0;
  const open = async (item: UserNotification) => {
    if (!item.readAt) await readMutation.mutateAsync(item.id);
    if (item.taskId) navigate(`/tasks/${item.taskId}`);
    else if (item.instanceId) navigate(`/processes/${item.instanceId}`);
  };

  return (
    <AppPage title="消息中心" description={`${unreadCount} 条未读消息`}>
      {notifications.length === 0 ? (
        <PageEmpty title="暂无流程消息" hint="任务到达、审批结果和流程变更会显示在这里。" />
      ) : (
        <section className="notification-list" aria-label="流程消息">
          {notifications.map((item) => (
            <button
              className={`notification-item${item.readAt ? '' : ' notification-item--unread'}`}
              key={item.id}
              type="button"
              onClick={() => void open(item)}
            >
              <span className="notification-item__dot" aria-hidden="true" />
              <span className="notification-item__body">
                <strong>{item.title}</strong>
                <small>{eventLabel(item.eventType)}</small>
              </span>
              <time>{formatTime(item.createdAt)}</time>
              <span className="notification-item__chev" aria-hidden="true">›</span>
            </button>
          ))}
        </section>
      )}
      {query.hasNextPage ? (
        <button className="btn btn--secondary btn--block notification-more" type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? '加载中…' : '加载更多'}
        </button>
      ) : null}
    </AppPage>
  );
}

function eventLabel(type: string) {
  const labels: Record<string, string> = {
    TASK_ASSIGNED: '待办任务',
    TASK_RETURNED: '退回修改',
    TASK_CANCELLED: '任务作废',
    APPROVAL_INVALIDATED: '审批作废',
    CC_ASSIGNED: '流程抄送',
    INSTANCE_APPROVED: '流程通过',
    INSTANCE_REJECTED: '流程驳回',
  };
  return labels[type] ?? '流程状态更新';
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export default NotificationCenterPage;
