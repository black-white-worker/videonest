import request from './request'
import type { ApiResponse } from './auth'

interface UploadResult {
  objectName: string
}

export interface CreateVideoRequest {
  categoryId: number
  title: string
  description: string
  coverObjectName: string
  videoObjectName: string
  duration: number
}

export interface CreateVideoResult {
  videoId: number
  status: string
}

async function uploadFile(url: string, file: File): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)

  const response = await request.post<ApiResponse<UploadResult>>(url, formData)

  return response.data.data.objectName
}

export function uploadCover(file: File): Promise<string> {
  return uploadFile('/files/cover', file)
}

export function uploadVideo(file: File): Promise<string> {
  return uploadFile('/files/video', file)
}

export async function createVideo(
  data: CreateVideoRequest
): Promise<CreateVideoResult> {
  const response = await request.post<ApiResponse<CreateVideoResult>>(
    '/creator/videos',
    data
  )

  return response.data.data
}