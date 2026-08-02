import request from './request'
import type { ApiResponse } from './auth'

interface UploadResult {
  objectName: string
  detectedDuration?: number
}

interface UploadPresignResult {
  uploadId: string
  objectName: string
  uploadUrl: string
  method: 'PUT'
  headers: Record<string, string>
  expiresInSeconds: number
}

export interface CreateVideoRequest {
  categoryId: number
  title: string
  description: string
  coverObjectName?: string
  videoObjectName: string
  duration: number
}

export interface CreateVideoResult {
  videoId: number
  status: string
}

async function uploadFile(
  type: 'cover' | 'video',
  file: File
): Promise<UploadResult> {
  const credential = await request.post<ApiResponse<UploadPresignResult>>(
    '/files/presign',
    {
      type,
      fileName: file.name,
      contentType: file.type,
      size: file.size
    }
  )
  const upload = credential.data.data
  const putResponse = await fetch(upload.uploadUrl, {
    method: upload.method,
    headers: upload.headers,
    body: file
  })
  if (!putResponse.ok) {
    throw new Error(`对象存储上传失败（${putResponse.status}）`)
  }
  const completed = await request.post<ApiResponse<UploadResult>>(
    `/files/uploads/${upload.uploadId}/complete`,
    undefined,
    { timeout: 180_000 }
  )
  return completed.data.data
}

export function uploadVideo(file: File): Promise<UploadResult> {
  return uploadFile('video', file)
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

export function uploadCover(file: File): Promise<string> {
  return uploadFile('cover', file).then(result => result.objectName)
}
