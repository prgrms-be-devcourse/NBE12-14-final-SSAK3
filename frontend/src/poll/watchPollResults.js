import { request } from '../stt/api.js'
import { openRoomSocket } from '../ws/roomSocket.js'
import { selectGeneralPolls } from './pollResults.js'
import { applyCounts, isResultsEvent } from './understandingSummary.js'

// onPolls(polls): 일반 투표의 결과 목록. 진행 중인 것이 먼저이고 없으면 빈 배열.
// 각 항목은 results API 의 값에 question 을 더한 것이다.
export function watchPollResults({ roomId, onPolls, onConnection, onDenied }) {
  const base = `/rooms/${encodeURIComponent(roomId)}/polls`
  const hostHeaders = { 'X-Chat-Role': 'HOST' }
  const finals = new Map()
  // 마감 이벤트를 받은 투표. 늦게 도착한 응답 이벤트가 확정된 집계를 덮어쓰지 않도록 이후 결과 이벤트를 무시한다.
  const closed = new Set()
  let active = true
  let revision = 0
  let current = []

  function show(polls) {
    current = polls
    onPolls(polls)
  }

  async function resultsOf(poll) {
    // 마감된 투표의 결과는 더 바뀌지 않으므로 한 번만 받는다.
    if (poll.status === 'CLOSED' && finals.has(poll.pollId)) return finals.get(poll.pollId)
    const results = { ...await request(`${base}/${encodeURIComponent(poll.pollId)}/results`), question: poll.question }
    if (results.status === 'CLOSED') finals.set(poll.pollId, results)
    return results
  }

  async function load() {
    const [open, closed] = await Promise.all([request(`${base}?status=OPEN`), request(`${base}?status=CLOSED`)])
    return Promise.all(selectGeneralPolls(open.polls, closed.polls).map(resultsOf))
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
    show([])
    onDenied?.(error)
  }

  function handlePoll(frame) {
    let event
    try { event = JSON.parse(frame.body) } catch { return }
    const data = event?.data
    if (data?.roomId !== Number(roomId)) return
    if (event.type === 'POLL_CLOSED') closed.add(data.pollId)
    // 새 투표가 열리거나 보던 투표가 마감되면 목록과 결과를 서버에서 다시 받는다.
    if ((event.type === 'POLL_CREATED' && data.type === 'GENERAL')
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
