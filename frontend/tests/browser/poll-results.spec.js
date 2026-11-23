import { expect, test } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

const roomId = 10
const base = `/api/v1/rooms/${roomId}`
const pollsTopic = `/topic/rooms/${roomId}/polls`
const resultsTopic = `${pollsTopic}/results`
const poll = (pollId, status, type = 'GENERAL') => ({ pollId, roomId, type, question: `질문 ${pollId}`, status })
const rate = (part, whole) => (whole ? Math.round(part * 1000 / whole) / 10 : 0)
const results = (pollId, status, counts, total) => {
  const responded = counts.reduce((sum, count) => sum + count, 0)
  return {
    pollId, type: 'GENERAL', status, closesAt: '2026-11-21T03:01:00Z',
    totalParticipants: total, respondedParticipants: responded, unrespondedParticipants: total - responded,
    responseRate: rate(responded, total),
    options: counts.map((count, index) => ({ optionId: pollId * 10 + index, content: `선택지 ${index + 1}`,
      understandingStatus: null, count, percentageOfResponses: rate(count, responded),
      percentageOfAllParticipants: rate(count, total) })),
    calculatedAt: '2026-11-21T03:00:30Z',
  }
}
const panel = page => page.getByRole('region', { name: '투표 현황' })
const card = (page, pollId) => panel(page).getByRole('listitem', { name: `질문 ${pollId}` })

async function setup(page, context, { open = [], closed = [], results: initial = {} } = {}) {
  await setupRecording(context)
  const state = { open, closed, results: initial, subscribed: new Set(), push: {}, senders: {}, reads: [], listReads: [], resultStatus: {} }
  await context.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      const message = frame.toString()
      if (message.startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      const destination = /^destination:(.+)$/m.exec(message)?.[1]
      if (message.startsWith('SUBSCRIBE\n') && destination) {
        const id = /^id:(.+)$/m.exec(message)[1]
        state.subscribed.add(destination)
        // 같은 토픽을 여러 패널이 구독하므로 구독자 모두에게 보낸다.
        const senders = (state.senders[destination] ??= [])
        senders.push(event => socket.send(`MESSAGE\ndestination:${destination}\nsubscription:${id}\nmessage-id:${crypto.randomUUID()}\n\n${JSON.stringify(event)}\0`))
        state.push[destination] = event => senders.forEach(send => send(event))
      }
    })
  })
  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url())
    const ok = data => route.fulfill({ json: { data } })
    if (url.pathname === base) {
      return ok({ roomId, title: '투표 현황 강연', description: '', status: 'LIVE',
        createdAt: '2026-11-21T00:00:00Z', startedAt: '2026-11-21T01:00:00Z', endedAt: null })
    }
    if (url.pathname === `${base}/invitation`) {
      return ok({ joinCode: 'WORKSHOP', joinUrl: 'http://127.0.0.1:5174/join/WORKSHOP', qrText: 'http://127.0.0.1:5174/join/WORKSHOP' })
    }
    if (url.pathname === `${base}/polls`) {
      state.listReads.push(url.searchParams.get('status'))
      const polls = url.searchParams.get('status') === 'OPEN' ? state.open : state.closed
      return ok({ polls, serverTime: '2026-11-21T03:00:00Z' })
    }
    const read = /\/polls\/(\d+)\/results$/.exec(url.pathname)
    if (read) {
      state.reads.push(Number(read[1]))
      if (state.resultStatus[read[1]]) return route.fulfill({ status: state.resultStatus[read[1]], json: { code: 'ERROR' } })
      return ok(state.results[read[1]])
    }
    if (url.pathname === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (url.pathname === `${base}/ai/analyses`) return ok({ roomId, analyses: [] })
    return route.fallback()
  })
  return state
}

test('진행 중인 투표는 응답 이벤트로 선택지별 인원과 비율이 갱신된다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: [poll(501, 'OPEN')], results: { 501: results(501, 'OPEN', [0, 0], 10) },
  })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(card(page, 501)).toBeVisible()
  await expect(card(page, 501).getByText('진행 중')).toBeVisible()
  await expect(card(page, 501)).toContainText('미응답 10명')

  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 10, respondedParticipants: 8, unrespondedParticipants: 2,
    counts: { 5010: 6, 5011: 2 } } })

  await expect(card(page, 501).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('6명')
  await expect(card(page, 501).getByRole('listitem').filter({ hasText: '선택지 1' }))
    .toContainText('응답자의 75.0% · 전체의 60.0%')
  await expect(card(page, 501)).toContainText('전체 참여 10명 · 응답 8명(80.0%) · 미응답 2명')
})

test('진행 중과 마감된 투표를 구분하고 마감 이벤트가 오면 최종 결과로 바꾼다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: [poll(502, 'OPEN')], closed: [poll(501, 'CLOSED')],
    results: { 501: results(501, 'CLOSED', [3, 1], 5), 502: results(502, 'OPEN', [1, 0], 5) },
  })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(card(page, 502).getByText('진행 중')).toBeVisible()
  await expect(card(page, 501).getByText('마감')).toBeVisible()
  await expect(panel(page).getByRole('listitem', { name: /^질문 / }).first()).toHaveAccessibleName('질문 502')

  await expect.poll(() => state.subscribed.has(pollsTopic)).toBe(true)
  state.open = []
  state.closed = [poll(502, 'CLOSED'), poll(501, 'CLOSED')]
  state.results[502] = results(502, 'CLOSED', [4, 1], 5)
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 502, roomId, closeReason: 'HOST' } })

  await expect(card(page, 502).getByText('마감')).toBeVisible()
  await expect(card(page, 502).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('4명')
  // 이미 마감된 투표의 결과는 다시 받지 않는다.
  expect(state.reads.filter(id => id === 501)).toHaveLength(1)
})

test('새 일반 투표가 열리면 목록에 나타나고 다른 투표의 이벤트와 이해도 체크는 무시한다', async ({ page, context }) => {
  const state = await setup(page, context, {
    closed: [poll(501, 'CLOSED')], results: { 501: results(501, 'CLOSED', [2, 2], 4) },
  })
  await page.goto(`/host/rooms/${roomId}`)
  await expect(card(page, 501)).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic) && state.subscribed.has(resultsTopic)).toBe(true)

  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 999, totalParticipants: 4, respondedParticipants: 4, unrespondedParticipants: 0, counts: { 5010: 4 } } })
  await expect(card(page, 501).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('2명')

  state.open = [poll(502, 'OPEN'), poll(900, 'OPEN', 'UNDERSTANDING')]
  state.results[502] = results(502, 'OPEN', [0, 0], 4)
  state.results[900] = { ...results(900, 'OPEN', [0, 0], 4), type: 'UNDERSTANDING' }
  state.push[pollsTopic]({ type: 'POLL_CREATED', data: { ...poll(502, 'OPEN'), type: 'GENERAL' } })

  await expect(card(page, 502).getByText('진행 중')).toBeVisible()
  // 이해도 체크 결과도 같은 조회에서 받지만 투표 현황에는 일반 투표만 나온다.
  await expect.poll(() => state.reads.includes(900)).toBe(true)
  await expect(card(page, 900)).toHaveCount(0)
})

test('투표가 한 번도 없으면 투표 현황을 보이지 않는다', async ({ page, context }) => {
  const state = await setup(page, context)
  await page.goto(`/host/rooms/${roomId}`)
  await expect(page.getByRole('heading', { name: '투표 현황 강연' })).toBeVisible()
  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  await expect(panel(page)).toHaveCount(0)
})

test('마감된 투표에 늦게 도착한 응답 이벤트는 확정된 결과를 덮어쓰지 않는다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: [poll(501, 'OPEN')], results: { 501: results(501, 'OPEN', [1, 0], 10) },
  })
  await page.goto(`/host/rooms/${roomId}`)
  await expect(card(page, 501).getByText('진행 중')).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic) && state.subscribed.has(resultsTopic)).toBe(true)

  state.open = []
  state.closed = [poll(501, 'CLOSED')]
  state.results[501] = results(501, 'CLOSED', [6, 2], 10)
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 501, roomId, closeReason: 'HOST' } })
  await expect(card(page, 501).getByText('마감')).toBeVisible()
  await expect(card(page, 501).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('6명')

  // 같은 토픽의 이벤트는 순서대로 처리되므로, 늦은 이벤트 뒤에 진행 중인 다른 투표의 이벤트를 보내 그것이 반영되면
  // 앞선 이벤트도 이미 처리된 것이다.
  state.open = [poll(502, 'OPEN')]
  state.results[502] = results(502, 'OPEN', [0, 0], 10)
  state.push[pollsTopic]({ type: 'POLL_CREATED', data: { ...poll(502, 'OPEN'), type: 'GENERAL' } })
  await expect(card(page, 502)).toBeVisible()
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 10, respondedParticipants: 1, unrespondedParticipants: 9, counts: { 5010: 1, 5011: 0 } } })
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 502, totalParticipants: 10, respondedParticipants: 3, unrespondedParticipants: 7, counts: { 5020: 3, 5021: 0 } } })
  await expect(card(page, 502).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('3명')
  await expect(card(page, 501).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('6명')
  await expect(card(page, 501)).toContainText('응답 8명')
})

test('이해도 체크 패널과 투표 현황은 같은 연결과 목록 조회를 함께 쓴다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: [poll(502, 'OPEN'), poll(900, 'OPEN', 'UNDERSTANDING')], closed: [poll(501, 'CLOSED')],
    results: { 501: results(501, 'CLOSED', [2, 2], 4), 502: results(502, 'OPEN', [1, 0], 4),
      900: { ...results(900, 'OPEN', [0, 0], 4), type: 'UNDERSTANDING' } },
  })
  await page.goto(`/host/rooms/${roomId}`)
  await expect(card(page, 502)).toBeVisible()
  await expect(page.getByRole('region', { name: '이해도 체크 결과' })).toBeVisible()

  // 패널이 둘이어도 결과 토픽 구독은 한 번이고, 마감 목록과 각 투표의 결과도 한 번씩만 받는다.
  // (OPEN 목록은 투표 생성 패널이 따로 폴링하므로 세지 않는다.)
  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  expect(state.senders[resultsTopic]).toHaveLength(1)
  expect(state.listReads.filter(status => status === 'CLOSED')).toHaveLength(1)
  expect([...state.reads].sort()).toEqual([501, 502, 900])
})

test('투표 하나의 결과 조회가 실패해도 나머지 목록은 계속 보인다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: [poll(503, 'OPEN')], closed: [poll(501, 'CLOSED'), poll(502, 'CLOSED')],
    results: { 501: results(501, 'CLOSED', [2, 2], 4), 503: results(503, 'OPEN', [1, 0], 4) },
  })
  state.resultStatus[502] = 404
  await page.goto(`/host/rooms/${roomId}`)

  // 404 인 투표만 빠지고 패널은 닫히지 않는다.
  await expect(card(page, 503)).toBeVisible()
  await expect(card(page, 501)).toBeVisible()
  await expect(card(page, 502)).toHaveCount(0)

  // 이후 갱신에서 503 조회가 일시적으로 실패해도 마지막으로 받은 값을 유지한다.
  await expect.poll(() => state.subscribed.has(pollsTopic)).toBe(true)
  state.resultStatus[503] = 500
  state.open = [poll(503, 'OPEN'), poll(504, 'OPEN')]
  state.results[504] = results(504, 'OPEN', [0, 0], 4)
  state.push[pollsTopic]({ type: 'POLL_CREATED', data: { ...poll(504, 'OPEN'), type: 'GENERAL' } })
  await expect(card(page, 504)).toBeVisible()
  await expect(card(page, 503).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('1명')
})
