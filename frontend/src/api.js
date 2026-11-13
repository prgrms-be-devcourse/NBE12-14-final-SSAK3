const tokenKey = 'csmatch.accessToken'
export const authStorage = {
  token: () => sessionStorage.getItem(tokenKey),
  email: () => sessionStorage.getItem('csmatch.email') || '',
  save(token, email) {
    sessionStorage.setItem(tokenKey, token)
    sessionStorage.setItem('csmatch.email', email)
  },
  clear() {
    sessionStorage.removeItem(tokenKey)
    sessionStorage.removeItem('csmatch.email')
    sessionStorage.removeItem('csmatch.lastSession')
  },
}

export class ApiError extends Error {
  constructor(code, message) { super(message); this.code = code }
}

export async function api(path, { method = 'GET', body, signal } = {}) {
  let response
  try {
    response = await fetch(`/api/v1${path}`, {
      method, signal,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(authStorage.token() ? { Authorization: `Bearer ${authStorage.token()}` } : {}),
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new ApiError('NETWORK', '서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.')
  }
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/')) {
      authStorage.clear()
      window.dispatchEvent(new Event('csmatch:expired'))
    }
    throw new ApiError(payload?.error?.code || 'SERVER',
      payload?.error?.message || '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.')
  }
  return payload?.data
}

export const post = (path, body) => api(path, { method: 'POST', ...(body !== undefined ? { body } : {}) })
