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