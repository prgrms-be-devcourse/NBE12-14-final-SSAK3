import { openRoomSocket } from '../ws/roomSocket.js'
import { findOpenUnderstanding, isClosedEvent, withDeadline } from './understanding.js'
import { understandingApi } from './understandingApi.js'

// onPoll({ poll, selected }): 지금 열린 이해도 체크와 내 선택. 열린 것이 없으면 null.
export function watchUnderstanding({ roomId, onPoll, onConnection, onDenied }) {
  const api = understandingApi(roomId)
  let active = true
  let revision = 0
  let currentId = null

  function show(poll, selected) {
    currentId = poll?.pollId ?? null
    onPoll(poll ? { poll, selected } : null)
  }

  // The simple broker has no event replay, so reconcile with REST after (re)connecting.
  async function sync() {
    if (!active) return
    const ticket = ++revision
    try {
      const { polls, serverTime } = await api.open()
      if (!active || ticket !== revision) return
      const open = findOpenUnderstanding(polls)
      if (!open) { show(null); return }
      const mine = await api.mine(open.pollId)
      if (!active || ticket !== revision) return
      show(withDeadline(open, serverTime), mine?.optionId ?? null)
    } catch (error) {
      if (!active || ticket !== revision) return
      if (error.status === 401 || error.status === 403 || error.status === 404) deny(error)
    }
  }

  function deny(error) {
    active = false
    socket.close()
    show(null)
    onDenied?.(error)
  }

  function handle(frame) {
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    const data = event?.data
    if (data?.roomId !== Number(roomId)) return
    if (event.type === 'POLL_CREATED' && data.type === 'UNDERSTANDING' && data.status === 'OPEN') {
      // 이벤트가 먼저 오면 진행 중인 조회 결과보다 새 것이므로 앞선 조회는 버린다.
      revision++
      show(withDeadline(data, data.serverTime), null)
    } else if (isClosedEvent(event, currentId)) {
      revision++
      show(null)
    }
  }

  const socket = openRoomSocket({
    role: 'PARTICIPANT',
    subscriptions: [{ destination: `/topic/rooms/${roomId}/polls`, handler: handle }],
    onConnect() {
      onConnection?.(true)
      void sync()
    },
    onDisconnect: () => onConnection?.(false),
    onStompError: () => void sync(),
    onAuthError: deny,
    refresh: sync,
  })

  return {
    sync,
    // 마감 뒤 응답은 서버가 409 POLL_CLOSED 로 거부한다.
    respond: (pollId, optionId) => api.respond(pollId, optionId),
    close() {
      active = false
      socket.close()
    },
  }
}
