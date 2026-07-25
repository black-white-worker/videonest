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