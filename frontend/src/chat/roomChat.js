import { ApiError, request } from '../stt/api.js'
import { openRoomSocket } from '../ws/roomSocket.js'
import { readHistory } from './history.js'

export function openRoomChat({ roomId, live, role, since, onMessages, onConnection, onHistory, onLost, onAck, isCurrentAttempt, onError, onAccessError }) {
  let active = true
  let revision = 0
  let refreshing = false
  let knownThrough = 0
  let socket
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
    socket?.close()
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
    if (event?.type !== 'CHAT_BATCH' || event.data?.roomId !== Number(roomId) || !Array.isArray(event.data.messages)) return
    // 서버가 짧은 간격의 채팅을 한 프레임에 묶어 보낸다. 항목마다 발신자가 보낸 X-Chat-Attempt 값이 attempt 로 실려 온다.
    const messages = event.data.messages.filter(message => message?.roomId === Number(roomId) && included(message))
    if (!messages.length) return
    onMessages(messages)
    for (const message of messages) onAck(message.attempt, message)
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
    socket = openRoomSocket({
      role,
      subscriptions: [{ destination: `/topic/rooms/${roomId}/chat`, headers: roleHeaders, handler: handleMessage }],
      onErrorQueue: handleError,
      onConnect() {
        onConnection(true)
        void refresh()
      },
      onDisconnect: disconnected,
      onStompError: () => void refresh(),
      onAuthError: deny,
      // The simple broker has no event replay. Reconcile after reconnect and when returning to the tab.
      refresh: () => { if (!refreshing) void refresh() },
    })
  }
  queueMicrotask(() => { if (active) void refresh() })

  return {
    refresh,
    send(content, attempt) {
      if (!active || !socket?.connected) throw new Error('CHAT_DISCONNECTED')
      socket.publish(`/app/rooms/${roomId}/chat`,
        { ...roleHeaders, 'X-Chat-Attempt': attempt }, JSON.stringify({ content }))
    },
    close() {
      active = false
      socket?.close()
    },
  }
}
