import request from './request'
import type { ApiResponse } from './auth'

interface UploadResult { objectName: string; detectedDuration?: number }
interface UploadPresignResult { uploadId: string; uploadUrl: string; method: 'PUT'; headers: Record<string, string> }
interface MultipartSession { sessionId: string; status: string; partSize: number; totalParts: number; uploadedParts: Array<{ partNumber: number; etag: string }> }
interface PartPresign { uploadUrl: string; headers: Record<string, string> }
export interface UploadProgress { loaded: number; total: number; speedBytesPerSecond: number; remainingSeconds?: number; mode: 'single' | 'multipart'; attempt: number }
export type UploadCancel = () => void
export interface CreateVideoRequest { categoryId: number; title: string; description: string; coverObjectName?: string; videoObjectName: string; duration: number }
export interface CreateVideoResult { videoId: number; status: string }

const MULTIPART_THRESHOLD = 400 * 1024 * 1024
const MAX_VIDEO_SIZE = 800 * 1024 * 1024
const SESSION_KEY_PREFIX = 'videonest.multipart.'
const fingerprint = (file: File) => `${file.name}:${file.size}:${file.lastModified}`
const sessionKey = (file: File) => SESSION_KEY_PREFIX + fingerprint(file)

function metric(mode: 'single' | 'multipart', event: string, size: number, durationMs: number, reason = '') {
  void request.post('/files/metrics', { mode, event, size, durationMs, reason }).catch(() => undefined)
}
function failureReason(error: unknown) {
  const message = error instanceof Error ? error.message : ''
  if (message.includes('timeout')) return 'timeout'
  const status = Number(message.match(/\b(4\d\d|5\d\d)\b/)?.[1])
  return status >= 500 ? 'storage_5xx' : status >= 400 ? 'storage_4xx' : message ? 'unknown' : 'network'
}
function put(url: string, headers: Record<string, string>, body: Blob, progress: (loaded: number) => void, signal: AbortSignal): Promise<string> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new Error('上传已取消')); return }
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url); xhr.timeout = 15 * 60 * 1000
    Object.entries(headers).forEach(([key, value]) => xhr.setRequestHeader(key, value))
    xhr.upload.onprogress = event => { if (event.lengthComputable) progress(event.loaded) }
    xhr.onerror = () => reject(new Error('network'))
    xhr.onabort = () => reject(new Error('上传已取消'))
    xhr.ontimeout = () => reject(new Error('timeout'))
    xhr.onload = () => xhr.status >= 200 && xhr.status < 300 ? resolve(xhr.getResponseHeader('ETag') || '') : reject(new Error(`对象存储上传失败（${xhr.status}）`))
    xhr.send(body)
    signal.addEventListener('abort', () => xhr.abort(), { once: true })
  })
}

async function single(type: 'cover' | 'video', file: File, report?: (p: UploadProgress) => void, onCancelReady?: (cancel: UploadCancel) => void): Promise<UploadResult> {
  const started = performance.now()
  const credential = await request.post<ApiResponse<UploadPresignResult>>('/files/presign', { type, fileName: file.name, contentType: file.type, size: file.size })
  const controller = new AbortController()
  onCancelReady?.(() => { controller.abort(); void request.delete(`/files/uploads/${credential.data.data.uploadId}`).catch(() => undefined) })
  metric('single', 'started', file.size, 0)
  let lastBytes = 0; let lastAt = performance.now()
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      await put(credential.data.data.uploadUrl, credential.data.data.headers, file, loaded => {
        const now = performance.now(); const speed = Math.max(0, (loaded - lastBytes) / Math.max((now - lastAt) / 1000, .1)); lastBytes = loaded; lastAt = now
        report?.({ loaded, total: file.size, speedBytesPerSecond: speed, remainingSeconds: speed > 0 ? (file.size - loaded) / speed : undefined, mode: 'single', attempt })
      }, controller.signal)
      const completed = await request.post<ApiResponse<UploadResult>>(`/files/uploads/${credential.data.data.uploadId}/complete`, undefined, { timeout: 180_000 })
      metric('single', 'complete_succeeded', file.size, performance.now() - started); return completed.data.data
    } catch (error) { if (controller.signal.aborted || attempt === 2) { metric('single', 'put_failed', file.size, performance.now() - started, controller.signal.aborted ? 'unknown' : failureReason(error)); throw error } }
  }
  throw new Error('上传失败')
}

async function multipart(file: File, report?: (p: UploadProgress) => void, onCancelReady?: (cancel: UploadCancel) => void): Promise<UploadResult> {
  const started = performance.now(); const key = sessionKey(file); let id = localStorage.getItem(key)
  const controller = new AbortController()
  const create = async () => {
    const response = await request.post<ApiResponse<MultipartSession>>('/files/multipart/sessions', { type: 'video', fileName: file.name, contentType: file.type, size: file.size, fingerprint: fingerprint(file) })
    id = response.data.data.sessionId; localStorage.setItem(key, id); metric('multipart', 'started', file.size, 0); return response.data.data
  }
  let session: MultipartSession
  try { session = id ? (await request.get<ApiResponse<MultipartSession>>(`/files/multipart/sessions/${id}`)).data.data : await create() }
  catch { localStorage.removeItem(key); session = await create() }
  onCancelReady?.(() => { controller.abort(); localStorage.removeItem(key); if (id) void request.delete(`/files/multipart/sessions/${id}`).catch(() => undefined) })
  const etags = new Map(session.uploadedParts.map(part => [part.partNumber, part.etag]))
  const loaded = new Map<number, number>(); session.uploadedParts.forEach(part => loaded.set(part.partNumber, Math.min(session.partSize, file.size - (part.partNumber - 1) * session.partSize)))
  let lastTotal = 0; let lastAt = performance.now()
  const update = () => { const total = [...loaded.values()].reduce((sum, item) => sum + item, 0); const now = performance.now(); const speed = Math.max(0, (total - lastTotal) / Math.max((now - lastAt) / 1000, .1)); lastTotal = total; lastAt = now; report?.({ loaded: total, total: file.size, speedBytesPerSecond: speed, remainingSeconds: speed > 0 ? (file.size - total) / speed : undefined, mode: 'multipart', attempt: 1 }) }
  const pending = Array.from({ length: session.totalParts }, (_, index) => index + 1).filter(number => !etags.has(number)); let cursor = 0
  const worker = async () => { while (cursor < pending.length) { if (controller.signal.aborted) throw new Error('上传已取消'); const partNumber = pending[cursor++]; const start = (partNumber - 1) * session.partSize; const chunk = file.slice(start, Math.min(start + session.partSize, file.size)); for (let attempt = 1; attempt <= 3; attempt++) try { const signed = await request.post<ApiResponse<PartPresign>>(`/files/multipart/sessions/${id}/parts/${partNumber}/presign`); if (controller.signal.aborted) throw new Error('上传已取消'); const etag = await put(signed.data.data.uploadUrl, signed.data.data.headers, chunk, value => { loaded.set(partNumber, value); update() }, controller.signal); if (!etag) throw new Error('对象存储未返回 ETag'); etags.set(partNumber, etag); loaded.set(partNumber, chunk.size); update(); break } catch (error) { if (controller.signal.aborted || attempt === 3) throw error; await new Promise(resolve => setTimeout(resolve, 500 * 2 ** attempt)) } } }
  try {
    await Promise.all(Array.from({ length: Math.min(3, pending.length) }, worker))
    const completed = await request.post<ApiResponse<UploadResult>>(`/files/multipart/sessions/${id}/complete`, { parts: [...etags].map(([partNumber, etag]) => ({ partNumber, etag })).sort((a, b) => a.partNumber - b.partNumber) }, { timeout: 300_000 })
    localStorage.removeItem(key); metric('multipart', 'complete_succeeded', file.size, performance.now() - started); return completed.data.data
  } catch (error) { metric('multipart', 'put_failed', file.size, performance.now() - started, failureReason(error)); throw error }
}

export function uploadVideo(file: File, progress?: (p: UploadProgress) => void, onCancelReady?: (cancel: UploadCancel) => void): Promise<UploadResult> {
  if (file.size > MAX_VIDEO_SIZE) return Promise.reject(new Error('视频文件不能超过 800MB'))
  return file.size > MULTIPART_THRESHOLD ? multipart(file, progress, onCancelReady) : single('video', file, progress, onCancelReady)
}
export function uploadCover(file: File): Promise<string> { return single('cover', file).then(result => result.objectName) }
export async function createVideo(data: CreateVideoRequest): Promise<CreateVideoResult> { return (await request.post<ApiResponse<CreateVideoResult>>('/creator/videos', data)).data.data }
