import { apiRequest } from '../../shared/api/http';

export interface UserNotification {
  id: number;
  eventType: string;
  title: string;
  instanceId?: number;
  taskId?: number;
  createdAt: string;
  readAt?: string;
}

export interface NotificationPage {
  items: UserNotification[];
  hasMore: boolean;
  unreadCount: number;
}

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
