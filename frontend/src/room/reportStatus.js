import { request } from '../stt/api.js'

export const receiptGraceMs = 2000
const reportStates = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED']

export function roomReportSource(roomId, endedAt, onAccessError) {
  const path = `/rooms/${encodeURIComponent(roomId)}/reports`

  async function read(method, url) {
    try {
      const report = await request(url, { method })
      if (!reportStates.includes(report?.status)) throw new Error('UNKNOWN_REPORT_STATUS')
      // A duplicate receipt may return a report that already failed. Only the status API
      // can tell the owner which action is still available.
      if (method === 'POST' && report.status === 'FAILED') return read('GET', `${path}/status`)
      return {
        status: report.status, retryAvailable: report.retryAvailable === true, delayed: report.delayed === true,
        failureCategory: report.status === 'FAILED' ? report.failureCategory ?? null : null,
      }
    } catch (error) {
      if (error.status === 409 && error.code === 'ROOM_NOT_ENDED') return { status: 'LIVE' }
      if (method !== 'GET' || error.status !== 404 || error.code !== 'REPORT_NOT_FOUND') throw error

      // Both times come from the server, even after refresh or with an inaccurate device clock.
      const remaining = Date.parse(endedAt) + receiptGraceMs - Date.parse(error.timestamp)
      if (!Number.isFinite(remaining)) throw error
      return remaining > 0
        ? { status: 'AWAITING_RECEIPT', checkAfterMs: remaining }
        : { status: 'NOT_CREATED', checkAfterMs: 30000 }
    }
  }
  return {
    load: () => read('GET', `${path}/status`),
    create: () => read('POST', path),
    retry: () => read('POST', `${path}/retry`),
    onAccessError,
  }
}
