import type { AntFlowAPI } from '../../../../shared/api.generated';
import type { Contract } from '../../shared/api/types';
import { apiRequest } from '../../shared/api/http';

export type UserNotification = Contract<
  AntFlowAPI.NotificationRow,
  'id' | 'eventType' | 'title' | 'createdAt'
>;

export type NotificationPage = Contract<
  Omit<AntFlowAPI.NotificationPage, 'items'>,
  'hasMore' | 'unreadCount'
> & { items: UserNotification[] };

export function fetchNotifications(page: number) {
  return apiRequest<NotificationPage>(
    `/api/mobile/notifications?page=${page}&pageSize=20`,
  );
}

export function markNotificationRead(id: number) {
  return apiRequest<void>(`/api/mobile/notifications/${id}/read`, {
    method: 'POST',
  });
}
