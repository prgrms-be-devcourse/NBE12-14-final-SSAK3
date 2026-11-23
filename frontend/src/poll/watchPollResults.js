import { request } from '../stt/api.js'
import { openRoomSocket } from '../ws/roomSocket.js'
import { recentClosedLimit, selectPolls } from './pollResults.js'
import { applyCounts, isResultsEvent } from './understandingSummary.js'

// 종류별로 보여 줄 마감 투표 개수. 결과를 투표마다 조회하므로 일반 투표에는 상한을 둔다.
const closedLimits = { GENERAL: recentClosedLimit, UNDERSTANDING: Infinity }
// 결과를 동시에 몇 개까지 요청할지.
const concurrency = 4
const rooms = new Map()

// type(GENERAL·UNDERSTANDING) 투표의 결과 목록을 onPolls(polls)로 알린다. 진행 중인 것이 먼저이고 없으면 빈 배열.
// 각 항목은 results API 의 값에 question·createdAt·closedAt 을 더한 것이다.
// 같은 강연방은 패널이 여럿이어도 WebSocket 연결과 목록 조회를 하나만 쓴다.
export function watchPollResults({ roomId, type, onPolls, onConnection, onDenied }) {
  const key = String(roomId)
  let room = rooms.get(key)
  if (!room) {
    room = openRoom(roomId, () => rooms.delete(key))
    rooms.set(key, room)
  }
  return room.join({ type, onPolls, onConnection, onDenied })
}

async function mapLimit(items, limit, task) {
  const out = new Array(items.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next++
      out[index] = await task(items[index])
    }
  }))
  return out
}

function openRoom(roomId, onEmpty) {
  const base = `/rooms/${encodeURIComponent(roomId)}/polls`
  const hostHeaders = { 'X-Chat-Role': 'HOST' }
  const listeners = new Set()
  const finals = new Map()
  // 마감 이벤트를 받은 투표. 늦게 도착한 응답 이벤트가 확정된 집계를 덮어쓰지 않도록 이후 결과 이벤트를 무시한다.
  const closed = new Set()
  let active = true
  let revision = 0
  let current = []
  let loaded = false
  let connected = false
  let denied = null

  function deliver(listener) {
    listener.onPolls(current.filter(poll => poll.type === listener.type))
  }

  function show(polls) {
    current = polls
    loaded = true
    listeners.forEach(deliver)
  }

  async function resultsOf(poll) {
    // 마감된 투표의 결과는 더 바뀌지 않으므로 한 번만 받는다.
    if (poll.status === 'CLOSED' && finals.has(poll.pollId)) return finals.get(poll.pollId)
    try {
      const results = { ...await request(`${base}/${encodeURIComponent(poll.pollId)}/results`),
        question: poll.question, createdAt: poll.createdAt, closedAt: poll.closedAt }
      if (results.status === 'CLOSED') finals.set(poll.pollId, results)
      return results
    } catch (error) {
      if (error.status === 401 || error.status === 403) throw error
      // 투표 하나의 조회 실패가 목록 전체를 막지 않도록 이전에 받은 값을 유지하고, 없으면 건너뛴다.
      return current.find(item => item.pollId === poll.pollId) ?? null
    }
  }

  async function load() {
    const [open, closedList] = await Promise.all([request(`${base}?status=OPEN`), request(`${base}?status=CLOSED`)])
    const selected = Object.keys(closedLimits)
      .flatMap(type => selectPolls(open.polls, closedList.polls, type, closedLimits[type]))
    return (await mapLimit(selected, concurrency, resultsOf)).filter(Boolean)
  }

  // The simple broker has no event replay, so reconcile with REST after (re)connecting.
  async function sync() {
    if (!active) return
    const ticket = ++revision
    try {
      const polls = await load()
      if (!active || ticket !== revision) return
      show(polls)
    } catch (error) {
      if (!active || ticket !== revision) return
      if (error.status === 401 || error.status === 403 || error.status === 404) deny(error)
    }
  }

  function deny(error) {
    active = false
    socket.close()
    denied = error
    current = []
    listeners.forEach(listener => { listener.onPolls([]); listener.onDenied?.(error) })
  }

  function handlePoll(frame) {
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    const data = event?.data
    if (data?.roomId !== Number(roomId)) return
    if (event.type === 'POLL_CLOSED') closed.add(data.pollId)
    // 새 투표가 열리거나 보던 투표가 마감되면 목록과 결과를 서버에서 다시 받는다.
    if ((event.type === 'POLL_CREATED' && data.type in closedLimits)
      || (event.type === 'POLL_CLOSED' && current.some(poll => poll.pollId === data.pollId))) void sync()
  }

  function handleResults(frame) {
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    const pollId = event?.data?.pollId
    // 진행 중인 투표의 응답만 반영한다. 마감된 투표의 집계는 서버가 확정한 값이다.
    if (!isResultsEvent(event) || closed.has(pollId)
      || !current.some(poll => poll.pollId === pollId && poll.status === 'OPEN')) return
    // 응답 이벤트가 먼저 오면 진행 중인 조회 결과보다 새 것이므로 앞선 조회는 버린다.
    revision++
    show(current.map(poll => applyCounts(poll, event.data)))
  }

  const socket = openRoomSocket({
    role: 'HOST',
    subscriptions: [
      { destination: `/topic/rooms/${roomId}/polls`, headers: hostHeaders, handler: handlePoll },
      { destination: `/topic/rooms/${roomId}/polls/results`, headers: hostHeaders, handler: handleResults },
    ],
    onConnect() {
      connected = true
      listeners.forEach(listener => listener.onConnection?.(true))
      void sync()
    },
    onDisconnect() {
      connected = false
      listeners.forEach(listener => listener.onConnection?.(false))
    },
    onStompError: () => void sync(),
    onAuthError: deny,
    refresh: sync,
  })

  return {
    join(listener) {
      listeners.add(listener)
      // 나중에 합류한 패널에는 이미 받아 둔 상태를 바로 알린다.
      if (denied) {
        listener.onPolls([])
        listener.onDenied?.(denied)
      } else {
        if (connected) listener.onConnection?.(true)
        if (loaded) deliver(listener)
      }
      return {
        sync,
        close() {
          listeners.delete(listener)
          if (listeners.size > 0) return
          active = false
          socket.close()
          onEmpty()
        },
      }
    },
  }
}
