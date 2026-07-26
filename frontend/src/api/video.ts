import request from './request'
import type { ApiResponse } from './auth'

export interface VideoCategory {
  id: number
  name: string
  sortNum: number
}

export interface VideoListItem {
  id: number
  title: string
  description: string
  coverUrl: string
  duration: number
  viewCount: number
  likeCount: number
  favoriteCount: number
  publishTime: string
  authorId: number
  authorNickname: string
  categoryId: number
  categoryName: string
}

export interface VideoDetail {
  id: number
  title: string
  description: string
  coverUrl: string
  videoUrl: string
  duration: number
  viewCount: number
  likeCount: number
  favoriteCount: number
  publishTime: string
  authorId: number
  authorUsername: string
  authorNickname: string
  categoryId: number
  categoryName: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

export async function getCategories(): Promise<VideoCategory[]> {
  const response = await request.get<ApiResponse<VideoCategory[]>>(
    '/categories'
  )

  return response.data.data
}

export async function getVideoList(params: {
  categoryId?: number
  page: number
  size: number
}): Promise<PageResult<VideoListItem>> {
  const response = await request.get<ApiResponse<PageResult<VideoListItem>>>(
    '/videos',
    { params }
  )

  return response.data.data
}

export async function getVideoDetail(id: number): Promise<VideoDetail> {
  const response = await request.get<ApiResponse<VideoDetail>>(
    `/videos/${id}`
  )

  return response.data.data
}

export interface InteractionStatus {
  liked: boolean
  favorited: boolean
  likeCount: number
  favoriteCount: number
}

export interface VideoComment {
  id: string
  videoId: number
  userId: number
  parentId: string
  content: string
  createdAt: string
  username: string
  nickname: string
  replyCount?: number
}

export async function getHotVideos(limit = 12): Promise<VideoListItem[]> {
  const response = await request.get<ApiResponse<VideoListItem[]>>('/videos/hot', {
    params: { limit }
  })

  return response.data.data
}

export async function getInteractionStatus(
  videoId: number
): Promise<InteractionStatus> {
  const response = await request.get<ApiResponse<InteractionStatus>>(
    `/videos/${videoId}/interaction`
  )

  return response.data.data
}

export async function likeVideo(videoId: number): Promise<void> {
  await request.post(`/videos/${videoId}/like`)
}

export async function unlikeVideo(videoId: number): Promise<void> {
  await request.delete(`/videos/${videoId}/like`)
}

export async function favoriteVideo(videoId: number): Promise<void> {
  await request.post(`/videos/${videoId}/favorite`)
}

export async function unfavoriteVideo(videoId: number): Promise<void> {
  await request.delete(`/videos/${videoId}/favorite`)
}

export async function getComments(
  videoId: number,
  page: number,
  size: number
): Promise<PageResult<VideoComment>> {
  const response = await request.get<ApiResponse<PageResult<VideoComment>>>(
    `/videos/${videoId}/comments`,
    { params: { page, size } }
  )

  return response.data.data
}

export async function createComment(
  videoId: number,
  content: string,
  parentId: string | number = 0
): Promise<void> {
  await request.post(`/videos/${videoId}/comments`, {
    parentId,
    content
  })
}

export async function getCommentReplies(
  videoId: number,
  commentId: string,
  page = 1,
  size = 50
): Promise<PageResult<VideoComment>> {
  const response = await request.get<ApiResponse<PageResult<VideoComment>>>(
    `/videos/${videoId}/comments/${commentId}/replies`,
    { params: { page, size } }
  )
  return response.data.data
}

export async function deleteComment(
  videoId: number,
  commentId: string
): Promise<void> {
  await request.delete(`/videos/${videoId}/comments/${commentId}`)
}
