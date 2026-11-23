import { useEffect, useRef, useState } from 'react'
import { request } from '../stt/api.js'
import { loginFor } from './state.js'
import { receiptGraceMs } from './reportStatus.js'

const endpoint = '/rooms/history'
const pollMs = 5000
const batchLimit = 50
const blank = { rows: null, nextCursor: null, baselineCount: 0, hasNew: false,
  loading: null, pageError: null, headError: false, statusError: false, forbidden: false }
const inProgress = status => status === 'PENDING' || status === 'PROCESSING'

function serverTimestamp(response) {
  const timestamp = Date.parse(response?.serverTime)
  if (!Number.isFinite(timestamp)) throw new Error('INVALID_HISTORY_SERVER_TIME')
  return timestamp
}

// The server truncates endedAt to seconds. A monotonic deadline keeps the receipt
// grace period independent of the lecturer's wall clock.
function observed(room, status, serverNow, graceChecked = false) {
  const remaining = Date.parse(room.endedAt) + receiptGraceMs - serverNow
  const awaiting = status === null && remaining > 0 && !graceChecked
  const delay = awaiting ? remaining : inProgress(status) ? pollMs : Infinity
  return { ...room, reportStatus: status, awaiting, checkAt: performance.now() + delay }
}

export function useRoomHistory(restored) {
  const [view, setView] = useState(() => restored?.view ?? blank)
  const actions = useRef(null)

  useEffect(() => {
    let state = restored?.view ?? blank
    let active = true
    let entered = false
    let generation = 0
    let polling = false
    let checkingHead = false
    let timer

    function update(changes) {
      state = { ...state, ...changes }
      setView(state)
    }
    function denied(error) {
      if (error.status !== 401 && error.status !== 403) return false
      clearTimeout(timer)
      update({ ...blank, forbidden: true })
      active = false
      if (error.status === 401) loginFor('/host/rooms/history')
      return true
    }
    function schedule() {
      clearTimeout(timer)
      if (!active || document.hidden || polling || !state.rows?.length) return
      const next = state.rows.reduce((time, row) => Math.min(time, row.checkAt), Infinity)
      if (Number.isFinite(next)) timer = setTimeout(checkDue, Math.max(0, Math.ceil(next - performance.now())))
    }
    function checkDue() {
      if (!active || document.hidden) return
      const now = performance.now()
      const due = state.rows.filter(row => row.checkAt <= now)
      if (due.length) void checkStatuses(due)
      else schedule()
    }
    async function checkStatuses(rows) {
      if (!active || document.hidden || polling || !rows.length) return
      polling = true
      clearTimeout(timer)
      const version = generation
      const now = performance.now()
      const expired = new Set(rows.filter(row => row.awaiting && row.checkAt <= now).map(row => row.roomId))
      const ids = rows.map(row => row.roomId)
      const requested = new Set(ids)
      try {
        const statuses = new Map()
        for (let i = 0; i < ids.length; i += batchLimit) {
          if (document.hidden) return
          const batch = ids.slice(i, i + batchLimit)
          const result = await request(`${endpoint}/statuses?roomIds=${batch.join(',')}`)
          if (!active || version !== generation) return
          const serverNow = serverTimestamp(result)
          for (const item of result.rooms) statuses.set(item.roomId, { status: item.reportStatus, serverNow })
        }
        // A room omitted by the owner-scoped server lookup is no longer available.
        const nextRows = state.rows.filter(row => !requested.has(row.roomId) || statuses.has(row.roomId))
          .map(row => {
            const item = statuses.get(row.roomId)
            return item ? observed(row, item.status, item.serverNow, expired.has(row.roomId)) : row
          })
        update({ rows: nextRows, statusError: false })
      } catch (error) {
        if (!active || version !== generation || denied(error)) return
        const retryAt = performance.now() + pollMs
        update({ statusError: true, rows: state.rows.map(row => requested.has(row.roomId)
          ? { ...row, checkAt: retryAt } : row) })
      } finally {
        polling = false
        schedule()
      }
    }
    async function loadPage(mode) {
      if (!active || state.loading || (mode === 'more' && !state.nextCursor)) return
      const cursor = mode === 'more' ? state.nextCursor : null
      update({ loading: mode, pageError: null })
      try {
        const page = await request(cursor ? `${endpoint}?cursor=${encodeURIComponent(cursor)}` : endpoint)
        if (!active) return
        const serverNow = serverTimestamp(page)
        const incoming = page.rooms.map(row => observed(row, row.reportStatus, serverNow))
        if (mode === 'more') {
          const previous = new Set(state.rows.map(row => row.roomId))
          update({ rows: [...state.rows, ...incoming.filter(row => !previous.has(row.roomId))],
            nextCursor: page.nextCursor, loading: null })
        } else {
          generation++
          update({ ...blank, rows: incoming, nextCursor: page.nextCursor, baselineCount: page.totalCount })
          if (mode === 'latest') window.scrollTo(0, 0)
        }
      } catch (error) {
        if (active && !denied(error)) update({ loading: null, pageError: mode })
      } finally { schedule() }
    }
    async function checkHead() {
      if (!active || checkingHead || state.hasNew || state.rows === null) return
      checkingHead = true
      const version = generation
      try {
        const latest = await request(endpoint + '/latest')
        if (!active || version !== generation) return
        update({ hasNew: latest.totalCount > state.baselineCount, headError: false })
      } catch (error) {
        if (active && version === generation && !denied(error)) update({ headError: true })
      } finally { checkingHead = false }
    }
    function visibility() {
      clearTimeout(timer)
      if (!active || document.hidden) return
      if (!entered) { enter(); return }
      if (state.rows === null) {
        if (!state.pageError) void loadPage('first')
        return
      }
      void checkHead()
      const now = performance.now()
      update({ rows: state.rows.map(row => inProgress(row.reportStatus) ? { ...row, checkAt: now } : row) })
      checkDue()
    }

    function enter() {
      if (!active || entered || document.hidden) return
      entered = true
      if (!restored) {
        void loadPage('first')
        return
      }
      // On Back, the selected room alone needs an immediate status check. Other
      // pending reports resume their normal interval after the list is restored.
      const nextPoll = performance.now() + pollMs
      update({ rows: state.rows.map(row => inProgress(row.reportStatus) ? { ...row, checkAt: nextPoll } : row) })
      const visited = state.rows?.find(row => row.roomId === restored.openedId)
      if (visited) void checkStatuses([visited])
      else schedule()
    }
    actions.current = { loadPage }
    document.addEventListener('visibilitychange', visibility)
    queueMicrotask(enter)
    return () => {
      active = false
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', visibility)
      actions.current = null
    }
  }, [restored])

  return { view, loadPage: mode => actions.current?.loadPage(mode) }
}
