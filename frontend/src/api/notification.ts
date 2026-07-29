import request from './request'
import type { ApiResponse } from './auth'
import type { PageResult } from './video'

export type NotificationType =
  | 'FOLLOW'
  | 'COMMENT'
  | 'REPLY'
  | 'LIKE'
  | 'FAVORITE'
  | 'REVIEW_TIMEOUT'
  | 'VIDEO_REJECTED'

export interface NotificationItem {
  id: number | string
  actorId: number
  actorNickname?: string
  type: NotificationType
  videoId?: number
  videoTitle?: string
  commentId?: string
  content?: string
  isRead: number
  createTime: string
}

export async function getNotifications(params: {
  page: number
  size: number
}): Promise<PageResult<NotificationItem>> {
  const response = await request.get<ApiResponse<PageResult<NotificationItem>>>(
    '/notifications',
    { params }
  )
  return response.data.data
}

export async function getUnreadNotificationCount(): Promise<number> {
  const response = await request.get<ApiResponse<number>>(
    '/notifications/unread-count'
  )
  return response.data.data
}

export async function markNotificationRead(
  notificationId: number | string
): Promise<void> {
  await request.put(`/notifications/${notificationId}/read`)
}
