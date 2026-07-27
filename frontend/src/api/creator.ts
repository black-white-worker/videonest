import request from './request'
import type { ApiResponse } from './auth'
import type { PageResult } from './video'
import type { VideoListItem } from './video'

export interface CreatorProfile {
  userId: number
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
  totalVideoCount: number
  pendingVideoCount: number
  publishedVideoCount: number
  rejectedVideoCount: number
  
}

export interface CreatorVideo {
  id: number
  title: string
  description: string
  coverUrl: string
  videoUrl: string

  // 后端返回的 MinIO 原始对象名，编辑提交时使用
  coverObjectName: string
  videoObjectName: string

  duration: number
  status: 'PROCESSING' | 'PROCESS_FAILED' | 'PENDING' | 'PUBLISHED' | 'REJECTED'
  rejectReason?: string
  processError?: string
  publishTime?: string
  createTime: string
  viewCount: number
  likeCount: number
  favoriteCount: number
  categoryId: number
  categoryName: string
}

export interface UpdateVideoRequest {
  categoryId: number
  title: string
  description: string
  coverObjectName: string
  videoObjectName: string
  duration: number
}

export async function getCreatorProfile(): Promise<CreatorProfile> {
  const response = await request.get<ApiResponse<CreatorProfile>>(
    '/creator/profile'
  )

  return response.data.data
}

export async function getCreatorVideos(params: {
  page: number
  size: number
}): Promise<PageResult<CreatorVideo>> {
  const response = await request.get<ApiResponse<PageResult<CreatorVideo>>>(
    '/creator/videos',
    { params }
  )

  return response.data.data
}

export async function updateCreatorVideo(
  videoId: number,
  data: UpdateVideoRequest
): Promise<void> {
  await request.put(`/creator/videos/${videoId}`, data)
}

export async function deleteCreatorVideo(videoId: number): Promise<void> {
  await request.delete(`/creator/videos/${videoId}`)
}

export async function getMyLikedVideos(params: { page: number; size: number }): Promise<PageResult<VideoListItem>> {
  const response = await request.get<ApiResponse<PageResult<VideoListItem>>>('/creator/videos/liked', { params })
  return response.data.data
}

export async function getMyFavoriteVideos(params: { page: number; size: number }): Promise<PageResult<VideoListItem>> {
  const response = await request.get<ApiResponse<PageResult<VideoListItem>>>('/creator/videos/favorites', { params })
  return response.data.data
}
