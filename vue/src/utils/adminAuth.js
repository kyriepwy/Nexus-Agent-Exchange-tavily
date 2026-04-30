const ADMIN_TOKEN_KEY = 'super-agent-admin-token'
const ADMIN_USER_KEY = 'super-agent-admin-user'

function decodeBase64Url(value) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padding = normalized.length % 4
  const base64 = padding ? normalized + '='.repeat(4 - padding) : normalized
  return window.atob(base64)
}

function parseTokenPayload(token) {
  if (!token) {
    return null
  }
  const parts = token.split('.')
  if (parts.length < 2) {
    return null
  }
  try {
    return JSON.parse(decodeBase64Url(parts[1]))
  } catch {
    return null
  }
}

function isTokenExpired(token) {
  const payload = parseTokenPayload(token)
  if (!payload?.exp) {
    return true
  }
  return Date.now() >= Number(payload.exp) * 1000
}

/**
 * 读取当前后台 token。
 */
export function getAdminToken() {
  return window.localStorage.getItem(ADMIN_TOKEN_KEY) || ''
}

/**
 * 判断当前后台 token 是否存在且仍有效。
 */
export function isAdminAuthenticated() {
  const token = getAdminToken()
  if (!token || isTokenExpired(token)) {
    clearAdminAuth()
    return false
  }
  return true
}

/**
 * 写入后台登录态。
 */
export function saveAdminAuth(payload = {}) {
  if (payload.token) {
    window.localStorage.setItem(ADMIN_TOKEN_KEY, payload.token)
  }
  if (payload.username) {
    window.localStorage.setItem(ADMIN_USER_KEY, payload.username)
  }
}

/**
 * 清理后台登录态。
 */
export function clearAdminAuth() {
  window.localStorage.removeItem(ADMIN_TOKEN_KEY)
  window.localStorage.removeItem(ADMIN_USER_KEY)
}

/**
 * 获取当前后台用户名。
 */
export function getAdminUsername() {
  return window.localStorage.getItem(ADMIN_USER_KEY) || 'admin'
}
