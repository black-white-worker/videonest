import axios, {
  type AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from 'axios'

interface ApiError {
  message?: string
}

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token')

    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }

    return config
  }
)

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const result = response.data

    if (result && result.code !== 200) {
      return Promise.reject(new Error(result.message || '请求失败'))
    }

    return response
  },
  (error: AxiosError<ApiError>) => {
    const message =
      error.response?.data?.message ||
      '网络异常，请检查 Spring Boot 后端是否已经启动'

    return Promise.reject(new Error(message))
  }
)

export default request