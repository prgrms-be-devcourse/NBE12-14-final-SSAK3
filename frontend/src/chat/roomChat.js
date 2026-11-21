import { Client } from '@stomp/stompjs'
import { ApiError, getCsrfToken, request } from '../stt/api.js'
import { readHistory } from './history.js'

export function openRoomChat({ roomId, live, role, since, onMessages, onConnection, onHistory, onLost, onAck, isCurrentAttempt, onError, onAccessError }) {
  let active = true
  let revision = 0
  let refreshing = false
  let knownThrough = 0
  let client
  const roleHeaders = { 'X-Chat-Role': role }
  const earliestSecond = Math.floor(Date.parse(since) / 1000)
  const path = `/rooms/${encodeURIComponent(roomId)}/messages?size=100`

  function included(message) {
    return Number.isSafeInteger(message?.messageId) && message.sender &&
        (!Number.isFinite(earliestSecond) || Math.floor(Date.parse(message.receivedAt) / 1000) >= earliestSecond)
  }

  async function refresh() {
    if (!active) return
    const ticket = ++revision
    const current = () => active && ticket === revision
    refreshing = true
    onHistory('loading')
    try {
      const messages = await readHistory(cursor => request(cursor === null ? path : `${path}&cursor=${cursor}`,
          { headers: roleHeaders }), knownThrough, current)
      if (!current()) return
      onMessages(messages.filter(included))
      knownThrough = Math.max(knownThrough, messages.at(-1)?.messageId ?? 0)
      onHistory('ready')
    } catch (error) {
      if (!current()) return
      if (error.status === 401 || error.status === 403 || error.code === 'ROOM_NOT_FOUND') {
        deny(error)
        return
      }
      onHistory('failed')
    } finally {
      if (current()) refreshing = false
    }
  }

  function deny(error) {
    active = false
    void client?.deactivate({ force: true })
    onAccessError(error)
  }

  function disconnected() {
    if (!active) return
    onConnection(false)
    onLost()
  }

  function handleMessage(frame) {
    if (!active) return
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    const message = event?.data
    if (event?.type !== 'CHAT_CREATED' || message?.roomId !== Number(roomId) || !included(message)) return
    onMessages([message])
    onAck(frame.headers['X-Chat-Attempt'], message)
  }

  function handleError(frame) {
    if (!active) return
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    if (event?.type !== 'ERROR' || !event.attempt || !isCurrentAttempt(event.attempt)) return
    if (event.code === 'UNAUTHENTICATED' || event.code === 'FORBIDDEN') {
      deny(new ApiError(event.code === 'UNAUTHENTICATED' ? 401 : 403, event.code))
      return
    }
    onError(event)
  }

  if (live) {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    client = new Client({
      brokerURL: `${scheme}://${location.host}/ws`,
      reconnectDelay: 3000,
      connectionTimeout: 8000,
      beforeConnect: async () => {
        try {
          // SCRUM-164: 새 연결과 재연결마다 현재 HTTP 세션의 CSRF 토큰을 확인한다.
          const { headerName, token } = await getCsrfToken(10000, true)
          if (!active) return
          client.connectHeaders = { ...roleHeaders, [headerName]: token }
        } catch (error) {
          if (active) {
            disconnected()
            if (error.status === 401 || error.status === 403) deny(error)
          }
          throw error
        }
      },
      onConnect() {
        if (!active) return
        client.subscribe(`/topic/rooms/${roomId}/chat`, handleMessage, roleHeaders)
        client.subscribe('/user/queue/errors', handleError)
        onConnection(true)
        void refresh()
      },
      onWebSocketClose: disconnected,
      onStompError: () => { disconnected(); void refresh() },
    })
    client.activate()
  }
  // The simple broker has no event replay. Reconcile after reconnect and when returning to the tab.
  const refreshVisible = () => { if (!document.hidden && navigator.onLine) void refresh() }
  const offline = () => { disconnected(); client?.forceDisconnect() }
  const timer = live ? window.setInterval(() => { if (!refreshing) refreshVisible() }, 30000) : null
  if (live) {
    document.addEventListener('visibilitychange', refreshVisible)
    window.addEventListener('online', refreshVisible)
    window.addEventListener('offline', offline)
  }
  queueMicrotask(() => { if (active) void refresh() })

  return {
    refresh,
    send(content, attempt) {
      if (!active || !client?.connected) throw new Error('CHAT_DISCONNECTED')
      client.publish({ destination: `/app/rooms/${roomId}/chat`,
        headers: { ...roleHeaders, 'X-Chat-Attempt': attempt }, body: JSON.stringify({ content }) })
    },
    close() {
      active = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refreshVisible)
      window.removeEventListener('online', refreshVisible)
      window.removeEventListener('offline', offline)
      void client?.deactivate({ force: true })
    },
  }
}