export class ApiError extends Error {
  constructor(status, code) {
    super(code || `HTTP_${status}`)
    this.status = status
    this.code = code
  }
}

export async function request(path, { method = 'GET', body, timeout = 10000 } = {}) {
  const response = await fetch(`/api/v1${path}`, {
    method,
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(timeout),
  })
  const json = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(response.status, json?.code)
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
