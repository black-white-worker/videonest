import request from './request'
import type { ApiResponse } from './auth'
import type { PageResult } from './video'

export interface AdminVideoReview {
  id: number
  title: string
  description: string
  coverUrl: string
  videoUrl: string
  duration: number
  status: 'PENDING' | 'PUBLISHED' | 'REJECTED'
  createTime: string
  authorId: number
  authorUsername: string
  authorNickname: string
  categoryId: number
  categoryName: string
  rejectReason?: string
  reviewDeadline?: string
  reviewTimeoutNotified?: number
  coverObjectName: string
  videoObjectName: string
}

export async function getPendingVideos(params: {
  page: number
  size: number
}): Promise<PageResult<AdminVideoReview>> {
  const response = await request.get<ApiResponse<PageResult<AdminVideoReview>>>(
    '/admin/videos/pending',
    { params }
  )

  return response.data.data
}

export async function reviewVideo(
  videoId: number,
  action: 'APPROVE' | 'REJECT',
  rejectReason?: string
): Promise<void> {
  await request.post<ApiResponse<null>>(
    `/admin/videos/${videoId}/review`,
    {
      action,
      rejectReason
    }
  )
}

export interface AdminUpdateVideoRequest {
  categoryId: number
  title: string
  description: string
  coverObjectName: string
  videoObjectName: string
  duration: number
}

export async function getAllVideos(params: {
  page: number
  size: number
}): Promise<PageResult<AdminVideoReview>> {
  const response = await request.get<ApiResponse<PageResult<AdminVideoReview>>>(
    '/admin/videos',
    { params }
  )

  return response.data.data
}

export async function updateAdminVideo(
  videoId: number,
  data: AdminUpdateVideoRequest
): Promise<void> {
  await request.put(`/admin/videos/${videoId}`, data)
}

export async function deleteAdminVideo(videoId: number): Promise<void> {
  await request.delete(`/admin/videos/${videoId}`)
}

export interface DeletedVideo {
  id: number
  title: string
  authorId: number
  authorNickname: string
  coverUrl?: string
  status: string
  deletedAt: string
  deletedBy: number
  purgeAfter: string
  purgeAttempts: number
  purgeError?: string
}

export async function getDeletedVideos(params: {
  page: number
  size: number
}): Promise<PageResult<DeletedVideo>> {
  const response = await request.get<ApiResponse<PageResult<DeletedVideo>>>(
    '/admin/videos/deleted',
    { params }
  )
  return response.data.data
}

export async function purgeDeletedVideo(videoId: number): Promise<void> {
  await request.delete(`/admin/videos/${videoId}/purge`)
}

export interface DeadLetterRecord {
  id: number
  queueName: string
  messageType: 'VIDEO_PROCESS' | 'NOTIFICATION' | 'REVIEW_TIMEOUT' | 'RESOURCE_PURGE'
  businessId?: string
  payload: string
  failureReason?: string
  status: 'PENDING' | 'RETRIED' | 'IGNORED'
  operatorId?: number
  handledAt?: string
  createTime: string
}

export async function getDeadLetters(params: {
  page: number
  size: number
  status?: string
}): Promise<PageResult<DeadLetterRecord>> {
  const response = await request.get<ApiResponse<PageResult<DeadLetterRecord>>>(
    '/admin/dead-letters',
    { params }
  )
  return response.data.data
}

export async function retryDeadLetter(id: number): Promise<void> {
  await request.post(`/admin/dead-letters/${id}/retry`)
}

export async function ignoreDeadLetter(id: number): Promise<void> {
  await request.put(`/admin/dead-letters/${id}/ignore`)
}

export interface AdminComment {
  id: string
  videoId: number
  videoTitle: string
  userId: number
  username: string
  nickname: string
  parentId: string
  content: string
  status: number
  createdAt: string
  deletedAt?: string
}

export async function getAdminComments(params: { page: number; size: number; keyword?: string; status?: number }): Promise<PageResult<AdminComment>> {
  const response = await request.get<ApiResponse<PageResult<AdminComment>>>('/admin/comments', { params })
  return response.data.data
}

export async function deleteAdminComment(commentId: string): Promise<void> {
  await request.delete(`/admin/comments/${commentId}`)
}

export async function restoreAdminComment(commentId: string): Promise<void> {
  await request.put(`/admin/comments/${commentId}/restore`)
}
