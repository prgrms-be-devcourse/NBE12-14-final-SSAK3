export class ApiError extends Error {
  constructor(status, code) {
    super(code || `HTTP_${status}`)
    this.status = status
    this.code = code
  }
}

let csrfPromise = null

export function getCsrfToken(timeout = 10000, refresh = false) {
  if (refresh) csrfPromise = null

  if (!csrfPromise) {
    csrfPromise = fetch('/api/v1/auth/csrf', {
      credentials: 'same-origin',
      cache: 'no-store',
      signal: AbortSignal.timeout(timeout),
    }).then(async response => {
      const json = await response.json().catch(() => null)

      if (!response.ok) {
        throw new ApiError(response.status, json?.code)
      }

      const { headerName, token } = json?.data ?? {}

      if (!headerName || !token) {
        throw new Error('CSRF_TOKEN_MISSING')
      }

      return { headerName, token }
    }).catch(error => {
      csrfPromise = null
      throw error
    })
  }

  return csrfPromise
}

export async function request(path, { method = 'GET', body, headers: extraHeaders, timeout = 10000 } = {}) {
  const normalizedMethod = method.toUpperCase()
  const needsCsrf = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(normalizedMethod)

  // 로그인·회원가입 시 현재 세션에 유효한 토큰을 다시 확인한다.
  if (path === '/auth/login' || path === '/auth/signup') {
    csrfPromise = null
  }

  const headers = { ...extraHeaders }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  if (needsCsrf) {
    const { headerName, token } = await getCsrfToken(timeout)
    headers[headerName] = token
  }

  const response = await fetch(`/api/v1${path}`, {
    method: normalizedMethod,
    credentials: 'same-origin',
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(timeout),
  })

  const json = await response.json().catch(() => null)

  // 세션 만료, 권한 거부, 로그아웃 시 캐시된 토큰을 폐기한다.
  if (
      response.status === 401 ||
      response.status === 403 ||
      (path === '/auth/logout' && response.ok)
  ) {
    csrfPromise = null
  }

  if (!response.ok) {
    throw new ApiError(response.status, json?.code)
  }

  return json?.data
}

export function sttApi(roomId) {
  const base = `/rooms/${encodeURIComponent(roomId)}/stt`
  return {
    me: () => request('/auth/me'),
    records: () => request(`${base}/segments`),
    start: startKey => request(`${base}/sessions`, { method: 'POST', body: { startKey } }),
    send: (sessionId, item) => {
      const session = `${base}/sessions/${sessionId}`
      const path = item.kind === 'segment' ? `${session}/segments`
          : item.kind === 'gapOpen' ? `${session}/gaps`
              : `${session}/gaps/${item.gapId}/close`
      return request(path, { method: item.kind === 'gapClose' ? 'PATCH' : 'POST', body: item.body })
    },
    stop: (sessionId, reason) => request(`${base}/sessions/${sessionId}/stop`, {
      method: 'POST', body: { reason },
    }),
  }
}

export const isTemporary = error => !error.status || error.status >= 500 || error.status === 429