export interface StoredUser {
  userId: number
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
}

export function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
}

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

export function getAccessToken(): string | null {
  const token = localStorage.getItem('token')
  if (token && isUsableJwt(token)) return token

  if (token || localStorage.getItem('userInfo')) clearSession()
  return null
}

export function hasValidSession(): boolean {
  return Boolean(getAccessToken())
}

export function getStoredUser(): StoredUser | null {
  if (!getAccessToken()) return null

  try {
    const value = localStorage.getItem('userInfo')
    return value ? (JSON.parse(value) as StoredUser) : null
  } catch {
    clearSession()
    return null
  }
}

export function saveSession(token: string, user: StoredUser) {
  localStorage.setItem('token', token)
  localStorage.setItem('userInfo', JSON.stringify(user))
}
