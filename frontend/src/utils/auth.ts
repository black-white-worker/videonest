import axios from 'axios'

export interface StoredUser {
  userId: number
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
}

/**
 * access token 只存在内存里（模块变量），不落 localStorage：
 * 页面刷新后由 bootstrapSession() 用 HttpOnly 的 refresh Cookie 换一个新的。
 */
let accessToken: string | null = null
let refreshPromise: Promise<string | null> | null = null

/** 仅用于路由守卫的“可能已登录”提示，不含任何凭证。 */
const SESSION_HINT = 'sessionHint'

const refreshClient = axios.create({
  baseURL: '/api',
  timeout: 10000,
  // 续期依赖 HttpOnly Cookie，必须允许携带凭证
  withCredentials: true
})

function isUsableJwt(token: string): boolean {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return false

    const normalized = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(
      atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '='))
    ) as {
      exp?: number
      nbf?: number
    }
    const now = Math.floor(Date.now() / 1000)

    return (
      typeof payload.exp === 'number' && payload.exp > now && (!payload.nbf || payload.nbf <= now)
    )
  } catch {
    return false
  }
}

/** 当前内存中的 access token；没有或已过期时返回 null。 */
export function getAccessToken(): string | null {
  if (accessToken && isUsableJwt(accessToken)) return accessToken
  return null
}

export function setAccessToken(token: string | null) {
  accessToken = token
}

/** 路由守卫用：内存里有 token，或者本地留有“上次登录过”的提示（可尝试续期）。 */
export function hasValidSession(): boolean {
  return Boolean(getAccessToken()) || localStorage.getItem(SESSION_HINT) === '1'
}

export function clearSession() {
  accessToken = null
  refreshPromise = null
  localStorage.removeItem('userInfo')
  localStorage.removeItem(SESSION_HINT)
}

export function getStoredUser(): StoredUser | null {
  try {
    const value = localStorage.getItem('userInfo')
    return value ? (JSON.parse(value) as StoredUser) : null
  } catch {
    clearSession()
    return null
  }
}

/** 登录成功后调用：access token 进内存，用户信息与“已登录提示”留在本地。 */
export function saveSession(token: string, user: StoredUser) {
  accessToken = token
  localStorage.setItem('userInfo', JSON.stringify(user))
  localStorage.setItem(SESSION_HINT, '1')
}

/**
 * 单飞续期：并发调用共享同一个 Promise，避免多个请求同时轮换 refresh token 互相顶掉。
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

async function doRefresh(): Promise<string | null> {
  try {
    const response = await refreshClient.post<{
      code: number
      message: string
      data: {
        token: string
        userId: number
        username: string
        nickname: string
        role: 'USER' | 'ADMIN'
      }
    }>('/auth/refresh')

    const session = response.data?.data
    if (!session?.token) {
      clearSession()
      return null
    }

    accessToken = session.token
    localStorage.setItem(
      'userInfo',
      JSON.stringify({
        userId: session.userId,
        username: session.username,
        nickname: session.nickname,
        role: session.role
      } satisfies StoredUser)
    )
    localStorage.setItem(SESSION_HINT, '1')
    return session.token
  } catch {
    clearSession()
    return null
  }
}

/** 应用启动时调用：页面刷新后内存 token 丢失，用 refresh Cookie 换回来。 */
export async function bootstrapSession(): Promise<void> {
  if (!hasValidSession()) {
    clearSession()
    return
  }
  await refreshAccessToken()
}
