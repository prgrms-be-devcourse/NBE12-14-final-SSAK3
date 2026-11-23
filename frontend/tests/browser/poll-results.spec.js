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
  const state = { open, closed, results: initial, subscribed: new Set(), push: {}, senders: {}, reads: [] }
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
      const polls = url.searchParams.get('status') === 'OPEN' ? state.open : state.closed
      return ok({ polls, serverTime: '2026-11-21T03:00:00Z' })
    }
    const read = /\/polls\/(\d+)\/results$/.exec(url.pathname)
    if (read) {
      state.reads.push(Number(read[1]))
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
  state.push[pollsTopic]({ type: 'POLL_CREATED', data: { ...poll(502, 'OPEN'), type: 'GENERAL' } })

  await expect(card(page, 502).getByText('진행 중')).toBeVisible()
  expect(state.reads).not.toContain(900)
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

  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 10, respondedParticipants: 1, unrespondedParticipants: 9, counts: { 5010: 1, 5011: 0 } } })
  // 늦은 이벤트가 처리될 시간을 주고도 최종 결과가 그대로인지 확인한다.
  await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 300)))
  await expect(card(page, 501).getByRole('listitem').filter({ hasText: '선택지 1' })).toContainText('6명')
  await expect(card(page, 501)).toContainText('응답 8명')
})
