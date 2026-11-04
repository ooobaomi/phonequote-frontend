import axios from 'axios'

export const api = axios.create({ baseURL: '/api/v1' })

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('phonequote_admin_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && !error.config?.url?.includes('/admin/auth/login')) {
      localStorage.removeItem('phonequote_admin_token')
      window.dispatchEvent(new Event('admin-session-expired'))
    }
    return Promise.reject(error)
  }
)

export function errorMessage(error: any): string {
  return error?.response?.data?.detail || error?.message || '操作失败，请重试'
}

