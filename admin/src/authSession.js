const TOKEN_KEY = 'admin_token'
const USER_KEY = 'admin_user'

export function getAdminToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function getAdminUser() {
  try {
    return JSON.parse(sessionStorage.getItem(USER_KEY) || '{}')
  } catch {
    clearAdminSession()
    return {}
  }
}

export function setAdminSession(token, user) {
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearAdminSession() {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
}
