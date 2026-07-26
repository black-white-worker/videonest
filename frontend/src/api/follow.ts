import request from './request'
import type { ApiResponse } from './auth'
import type { PageResult } from './video'

export interface FollowStatus {
  followed: boolean
}

export interface FollowUser {
  id: number
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
  followedAt: string
}

export async function getFollowStatus(userId: number): Promise<FollowStatus> {
  const response = await request.get<ApiResponse<FollowStatus>>(
    `/users/${userId}/follow/status`
  )
  return response.data.data
}

export async function followUser(userId: number): Promise<void> {
  await request.post(`/users/${userId}/follow`)
}

export async function unfollowUser(userId: number): Promise<void> {
  await request.delete(`/users/${userId}/follow`)
}

export async function getMyFollowing(params: { page: number; size: number }) {
  const response = await request.get<ApiResponse<PageResult<FollowUser>>>(
    '/users/me/following',
    { params }
  )
  return response.data.data
}

export async function getMyFollowers(params: { page: number; size: number }) {
  const response = await request.get<ApiResponse<PageResult<FollowUser>>>(
    '/users/me/followers',
    { params }
  )
  return response.data.data
}
