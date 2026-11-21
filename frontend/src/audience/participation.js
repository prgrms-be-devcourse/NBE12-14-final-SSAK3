import { request } from '../stt/api.js'

function publicPath(code) {
  return `/public/rooms/${encodeURIComponent(code)}`
}

export function endReason(error) {
  if (error.status === 404 && ['INVALID_JOIN_CODE', 'ROOM_NOT_FOUND'].includes(error.code)) return 'unavailable'
  if (error.code === 'ROOM_ENDED') return 'ended'
  return null
}

export async function readParticipation(code, recheckMissingSession = false) {
  let room = await request(publicPath(code))
  if (room.status === 'ENDED') return { room, participant: null }
  try {
    const participant = await request(`/rooms/${room.roomId}/participants/me`)
    return { room, participant }
  } catch (error) {
    if (error.status !== 401) throw error
    // A deleted room also invalidates its cookies. Check the room before asking a waiting participant to rejoin.
    if (recheckMissingSession) room = await request(publicPath(code))
    return { room, participant: null }
  }
}

export function joinRoom(code, attempt) {
  return request(`${publicPath(code)}/participants`, {
    method: 'POST',
    headers: { 'Idempotency-Key': attempt.key },
    body: { nickname: attempt.nickname },
  })
}

const storageKey = code => `react:audience:join:${code}`

export function loadAttempt(code, roomId) {
  try {
    const attempt = JSON.parse(sessionStorage.getItem(storageKey(code)))
    if (attempt?.roomId === roomId && typeof attempt.nickname === 'string' &&
      (typeof attempt.key === 'string' || attempt.key === null)) return attempt
  } catch { /* A blocked tab store can still open an existing participant session. */ }
  return null
}

export function saveAttempt(code, attempt) {
  sessionStorage.setItem(storageKey(code), JSON.stringify(attempt))
}

export function clearAttempt(code) {
  try { sessionStorage.removeItem(storageKey(code)) } catch { /* Storage may be disabled. */ }
}
