import axios from 'axios'
import { clearAdminSession, getAdminToken } from './authSession.js'

const http = axios.create({ baseURL: '/api/v1', timeout: 30000 })

http.interceptors.request.use(cfg => {
  const token = getAdminToken()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  r => r.data,
  err => {
    if (err.response?.status === 401) {
      clearAdminSession()
      window.location.href = '/login?reason=expired'
    }
    const payload = err.response?.data
    const message = payload?.error || payload?.message || err.message || '请求失败'
    return Promise.reject(typeof message === 'string' ? message : JSON.stringify(message))
  }
)

export default http
