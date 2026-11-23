import { request } from '../stt/api.js'
import { openRoomSocket } from '../ws/roomSocket.js'
import { findOpenUnderstanding } from './understanding.js'
import { applyCounts, isResultsEvent, latestUnderstanding } from './understandingSummary.js'

// onResults(results): 진행 중인 이해도 체크의 집계. 없으면 가장 최근에 마감된 체크의 최종 집계, 둘 다 없으면 null.
export function watchUnderstandingSummary({ roomId, onResults, onConnection, onDenied }) {
  const base = `/rooms/${encodeURIComponent(roomId)}/polls`
  const hostHeaders = { 'X-Chat-Role': 'HOST' }
  let active = true
  let revision = 0
  let current = null
  // 마감 이벤트를 받은 투표. 늦게 도착한 응답 이벤트가 확정된 집계를 덮어쓰지 않도록 이후 결과 이벤트를 무시한다.
  const closed = new Set()

  function show(results) {
    current = results
    onResults(results)
  }

  async function load() {
    const open = findOpenUnderstanding((await request(`${base}?status=OPEN`)).polls)
    const poll = open ?? latestUnderstanding((await request(`${base}?status=CLOSED`)).polls)
    return poll ? request(`${base}/${encodeURIComponent(poll.pollId)}/results`) : null
  }

  // The simple broker has no event replay, so reconcile with REST after (re)connecting.
  async function sync() {
    if (!active) return
    const ticket = ++revision
    try {
      const results = await load()
      if (!active || ticket !== revision) return
      show(results)
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

  function handlePoll(frame) {
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    const data = event?.data
    if (data?.roomId !== Number(roomId)) return
    if (event.type === 'POLL_CLOSED') closed.add(data.pollId)
    // 새 체크가 열리거나 보던 체크가 마감되면 집계를 서버에서 다시 받는다. 마감 값은 서버가 확정한다.
    if ((event.type === 'POLL_CREATED' && data.type === 'UNDERSTANDING')
      || (event.type === 'POLL_CLOSED' && data.pollId === current?.pollId)) void sync()
  }

  function handleResults(frame) {
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    // 진행 중인 체크의 응답만 반영한다. 마감된 체크의 집계는 서버가 확정한 값이다.
    if (!isResultsEvent(event) || !current || current.status !== 'OPEN' || closed.has(current.pollId)
      || event.data?.pollId !== current.pollId) return
    // 응답 이벤트가 먼저 오면 진행 중인 조회 결과보다 새 것이므로 앞선 조회는 버린다.
    revision++
    show(applyCounts(current, event.data))
  }

  const socket = openRoomSocket({
    role: 'HOST',
    subscriptions: [
      { destination: `/topic/rooms/${roomId}/polls`, headers: hostHeaders, handler: handlePoll },
      { destination: `/topic/rooms/${roomId}/polls/results`, headers: hostHeaders, handler: handleResults },
    ],
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
    close() {
      active = false
      socket.close()
    },
  }
}
