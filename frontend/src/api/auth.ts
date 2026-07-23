import request from './request'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface RegisterRequest {
  username: string
  password: string
  nickname: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: number
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
}

export async function register(data: RegisterRequest): Promise<void> {
  await request.post<ApiResponse<null>>('/auth/register', data)
}

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const response = await request.post<ApiResponse<LoginResponse>>(
    '/auth/login',
    data
  )

  return response.data.data
}