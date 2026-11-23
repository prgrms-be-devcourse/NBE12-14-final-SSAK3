import { request } from '../stt/api.js'

export const receiptGraceMs = 2000
const reportStates = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED']

export function roomReportSource(roomId, endedAt, onAccessError) {
  const path = `/rooms/${encodeURIComponent(roomId)}/reports`

  async function read(method) {
    try {
      const report = method === 'POST'
        ? await request(path, { method })
        : await request(`${path}/status`)
      if (!reportStates.includes(report?.status)) throw new Error('UNKNOWN_REPORT_STATUS')
      return { status: report.status }
    } catch (error) {
      if (error.status === 409 && error.code === 'ROOM_NOT_ENDED') return { status: 'LIVE' }
      if (method !== 'GET' || error.status !== 404 || error.code !== 'REPORT_NOT_FOUND') throw error

      // Both times come from the server, even after refresh or with an inaccurate device clock.
      const remaining = Date.parse(endedAt) + receiptGraceMs - Date.parse(error.timestamp)
      if (!Number.isFinite(remaining)) throw error
      return remaining > 0
        ? { status: 'AWAITING_RECEIPT', checkAfterMs: remaining }
        : { status: 'NOT_CREATED' }
    }
  }
  return { load: () => read('GET'), create: () => read('POST'), onAccessError }
}
