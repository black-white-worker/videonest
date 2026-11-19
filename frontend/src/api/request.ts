import axios, { type AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { logger } from '../utils/logger'
import { clearSession, getAccessToken, refreshAccessToken } from '../utils/auth'

interface ApiError {
  message?: string
}

type TimedRequestConfig = InternalAxiosRequestConfig & {
  requestStartedAt?: number
  __retried?: boolean
}

const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
  // refresh token 走 HttpOnly Cookie，续期与登出都需要携带凭证
  withCredentials: true,
  // 评论使用雪花 ID，超过 JavaScript Number 的安全整数范围。
  // 必须在 JSON.parse 前将其改为字符串，避免请求回复/删除时丢失精度。
  transformResponse: [
    (data: string) => {
      if (!data) {
        return data
      }

      try {
        const safeData = data.replace(/"(id|parentId)"\s*:\s*(-?\d{16,})/g, '"$1":"$2"')
        return JSON.parse(safeData)
      } catch {
        return data
      }
    }
  ]
})

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken()

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  ;(config as TimedRequestConfig).requestStartedAt = performance.now()
  logger.info('api.request.started', {
    method: config.method?.toUpperCase(),
    url: config.url
  })
  return config
})

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const result = response.data
    const config = response.config as TimedRequestConfig
    logger.info('api.request.succeeded', {
      method: config.method?.toUpperCase(),
      url: config.url,
      status: response.status,
      durationMs: config.requestStartedAt
        ? Math.round(performance.now() - config.requestStartedAt)
        : undefined
    })

    if (result && result.code !== 200) {
      logger.warn('api.business.failed', {
        url: config.url,
        code: result.code,
        message: result.message
      })
      return Promise.reject(new Error(result.message || '请求失败'))
    }

    return response
  },
  (error: AxiosError<ApiError>) => {
    const config = error.config as TimedRequestConfig | undefined
    const message = error.response?.data?.message || '网络异常，请检查 Spring Boot 后端是否已经启动'

    logger.error('api.request.failed', {
      method: config?.method?.toUpperCase(),
      url: config?.url,
      status: error.response?.status,
      code: error.code,
      durationMs: config?.requestStartedAt
        ? Math.round(performance.now() - config.requestStartedAt)
        : undefined,
      message
    })

    if (error.response?.status === 401) {
      return handleUnauthorized(config, message)
    }
    return Promise.reject(new Error(message))
  }
)

/**
 * 401 处理：access token 只有 15 分钟，过期很常见。
 * 先尝试用 refresh Cookie 换一个新令牌并重放原请求；续期失败才清会话跳登录。
 */
async function handleUnauthorized(
  config: TimedRequestConfig | undefined,
  message: string
): Promise<AxiosResponse> {
  const url = config?.url ?? ''
  // 认证接口自身返回 401 时不再续期，避免循环
  const isAuthEndpoint = url.includes('/auth/')
  const canRetry = Boolean(config) && !config?.__retried && !isAuthEndpoint

  if (canRetry && config) {
    config.__retried = true
    const token = await refreshAccessToken()
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`)
      logger.info('api.auth.refreshed', { url })
      return request(config)
    }
  }

  clearSession()
  if (window.location.pathname !== '/login') {
    const redirect = `${window.location.pathname}${window.location.search}`
    window.location.assign(`/login?redirect=${encodeURIComponent(redirect)}`)
  }
  throw new Error(message || '登录已过期，请重新登录')
}

export default request
