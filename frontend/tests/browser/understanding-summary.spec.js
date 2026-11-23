import { expect, test } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

const roomId = 10
const base = `/api/v1/rooms/${roomId}`
const pollsTopic = `/topic/rooms/${roomId}/polls`
const resultsTopic = `${pollsTopic}/results`
const options = [
  { optionId: 1, content: '이해됨', understandingStatus: 'UNDERSTOOD' },
  { optionId: 2, content: '헷갈림', understandingStatus: 'CONFUSED' },
  { optionId: 3, content: '모르겠음', understandingStatus: 'NOT_UNDERSTOOD' },
]
const row = (count, responded, total) => [count, Math.round(count * 1000 / (responded || 1)) / 10,
  Math.round(count * 1000 / (total || 1)) / 10]
const results = (status, counts, total) => {
  const responded = counts.reduce((sum, count) => sum + count, 0)
  return {
    pollId: 501, type: 'UNDERSTANDING', status, closesAt: '2026-11-21T03:01:00Z',
    totalParticipants: total, respondedParticipants: responded, unrespondedParticipants: total - responded,
    responseRate: Math.round(responded * 1000 / total) / 10,
    options: options.map((option, index) => {
      const [count, ofResponses, ofAll] = row(counts[index], responded, total)
      return { ...option, count, percentageOfResponses: ofResponses, percentageOfAllParticipants: ofAll }
    }),
    calculatedAt: '2026-11-21T03:00:30Z',
  }
}
const summary = page => page.getByRole('region', { name: '이해도 체크 결과' })

async function setup(page, context, { open = null, closed = [] } = {}) {
  await setupRecording(context)
  const state = { open, closed, results: null, subscribed: new Set(), push: {}, senders: {}, resultReads: 0 }
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
    const request = route.request()
    const url = new URL(request.url())
    const ok = data => route.fulfill({ json: { data } })
    if (url.pathname === base) {
      return ok({ roomId, title: '이해도 테스트 강연', description: '', status: 'LIVE',
        createdAt: '2026-11-21T00:00:00Z', startedAt: '2026-11-21T01:00:00Z', endedAt: null })
    }
    if (url.pathname === `${base}/invitation`) {
      return ok({ joinCode: 'WORKSHOP', joinUrl: 'http://127.0.0.1:5174/join/WORKSHOP', qrText: 'http://127.0.0.1:5174/join/WORKSHOP' })
    }
    if (url.pathname === `${base}/polls`) {
      const polls = url.searchParams.get('status') === 'OPEN' ? (state.open ? [state.open] : []) : state.closed
      return ok({ polls, serverTime: '2026-11-21T03:00:00Z' })
    }
    if (url.pathname === `${base}/polls/501/results`) { state.resultReads++; return ok(state.results) }
    if (url.pathname === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (url.pathname === `${base}/ai/analyses`) return ok({ roomId, analyses: [] })
    return route.fallback()
  })
  return state
}

const understanding = status => ({ pollId: 501, roomId, type: 'UNDERSTANDING', status, options })

test('진행 중인 이해도 체크는 응답 이벤트로 인원·비율이 갱신되고 미응답은 따로 보인다', async ({ page, context }) => {
  const state = await setup(page, context, { open: understanding('OPEN') })
  state.results = results('OPEN', [0, 0, 0], 20)
  await page.goto(`/host/rooms/${roomId}`)

  await expect(summary(page)).toBeVisible()
  await expect(summary(page).getByText('진행 중')).toBeVisible()
  await expect(summary(page).getByText('미응답', { exact: true }).locator('xpath=..')).toContainText('20명')

  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 20, respondedParticipants: 12, unrespondedParticipants: 8,
    counts: { 1: 6, 2: 4, 3: 2 } } })

  const understood = summary(page).getByRole('listitem').filter({ hasText: '이해됨' })
  await expect(understood).toContainText('6명')
  await expect(understood).toContainText('응답자의 50.0% · 전체의 30.0%')
  await expect(summary(page).getByRole('listitem').filter({ hasText: '모르겠음' })).toContainText('2명')
  await expect(summary(page).getByText('응답', { exact: true }).locator('xpath=..')).toContainText('12명 (60.0%)')
  await expect(summary(page).getByText('미응답', { exact: true }).locator('xpath=..')).toContainText('8명')
})

test('다른 투표의 결과 이벤트는 무시하고 마감되면 최종 결과를 다시 받는다', async ({ page, context }) => {
  const state = await setup(page, context, { open: understanding('OPEN') })
  state.results = results('OPEN', [1, 0, 0], 10)
  await page.goto(`/host/rooms/${roomId}`)
  await expect(summary(page)).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic)).toBe(true)
  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)

  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 999, totalParticipants: 10, respondedParticipants: 9, unrespondedParticipants: 1, counts: { 1: 9 } } })
  await expect(summary(page).getByRole('listitem').filter({ hasText: '이해됨' })).toContainText('1명')

  state.open = null
  state.closed = [{ ...understanding('CLOSED'), closedAt: '2026-11-21T03:01:00Z' }]
  state.results = results('CLOSED', [5, 3, 1], 12)
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 501, roomId, closeReason: 'TIMEOUT' } })

  await expect(summary(page).getByText('마감', { exact: true })).toBeVisible()
  await expect(summary(page).getByRole('listitem').filter({ hasText: '이해됨' })).toContainText('5명')
  await expect(summary(page).getByText('미응답', { exact: true }).locator('xpath=..')).toContainText('3명')
})

test('진행 중인 체크가 없으면 가장 최근에 마감된 체크의 최종 결과를 보여 준다', async ({ page, context }) => {
  const state = await setup(page, context, {
    closed: [{ ...understanding('CLOSED'), pollId: 400 }, { ...understanding('CLOSED'), closedAt: '2026-11-21T03:01:00Z' }],
  })
  state.results = results('CLOSED', [2, 2, 2], 6)
  await page.goto(`/host/rooms/${roomId}`)

  await expect(summary(page).getByText('마감', { exact: true })).toBeVisible()
  await expect(summary(page).getByRole('listitem').filter({ hasText: '헷갈림' })).toContainText('2명')
  expect(state.resultReads).toBeGreaterThan(0)
})

test('이해도 체크가 한 번도 없으면 결과 패널을 보이지 않는다', async ({ page, context }) => {
  const state = await setup(page, context)
  await page.goto(`/host/rooms/${roomId}`)
  await expect(page.getByRole('heading', { name: '이해도 테스트 강연' })).toBeVisible()
  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  await expect(summary(page)).toHaveCount(0)
})

test('마감된 이해도 체크에 늦게 도착한 응답 이벤트는 확정된 결과를 덮어쓰지 않는다', async ({ page, context }) => {
  const state = await setup(page, context, { open: understanding('OPEN') })
  state.results = results('OPEN', [1, 0, 0], 10)
  await page.goto(`/host/rooms/${roomId}`)
  await expect(summary(page).getByText('진행 중')).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic) && state.subscribed.has(resultsTopic)).toBe(true)

  state.open = null
  state.closed = [{ ...understanding('CLOSED'), closedAt: '2026-11-21T03:01:00Z' }]
  state.results = results('CLOSED', [5, 3, 1], 12)
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 501, roomId, closeReason: 'HOST' } })
  await expect(summary(page).getByText('마감', { exact: true })).toBeVisible()
  await expect(summary(page).getByRole('listitem').filter({ hasText: '이해됨' })).toContainText('5명')

  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 12, respondedParticipants: 1, unrespondedParticipants: 11, counts: { 1: 1 } } })
  await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 300)))
  await expect(summary(page).getByRole('listitem').filter({ hasText: '이해됨' })).toContainText('5명')
  await expect(summary(page).getByText('미응답', { exact: true }).locator('xpath=..')).toContainText('3명')
})
