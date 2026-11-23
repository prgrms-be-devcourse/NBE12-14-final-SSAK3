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
const rate = (part, whole) => (whole ? Math.round(part * 1000 / whole) / 10 : 0)
const results = (pollId, status, counts, total) => {
  const responded = counts.reduce((sum, count) => sum + count, 0)
  return {
    pollId, type: 'UNDERSTANDING', status, closesAt: '2026-11-21T03:01:00Z',
    totalParticipants: total, respondedParticipants: responded, unrespondedParticipants: total - responded,
    responseRate: rate(responded, total),
    options: options.map((option, index) => ({ ...option, count: counts[index],
      percentageOfResponses: rate(counts[index], responded), percentageOfAllParticipants: rate(counts[index], total) })),
    calculatedAt: '2026-11-21T03:00:30Z',
  }
}
const understanding = (pollId, status) => ({ pollId, roomId, type: 'UNDERSTANDING', status, options,
  createdAt: '2026-11-21T03:00:00Z', closedAt: status === 'CLOSED' ? '2026-11-21T03:01:00Z' : null })
const summary = page => page.getByRole('region', { name: '이해도 체크 결과' })
const card = (page, number) => summary(page).getByRole('listitem', { name: `이해도 체크 ${number}`, exact: true })
const option = (page, number, name) => card(page, number).getByRole('listitem').filter({ hasText: name })
const total = (page, number, name) => card(page, number).getByText(name, { exact: true }).locator('xpath=..')

async function setup(page, context, { open = null, closed = [], results: initial = {} } = {}) {
  await setupRecording(context)
  const state = { open, closed, results: initial, subscribed: new Set(), push: {}, senders: {}, resultReads: [] }
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
    const read = /\/polls\/(\d+)\/results$/.exec(url.pathname)
    if (read) { state.resultReads.push(Number(read[1])); return ok(state.results[read[1]]) }
    if (url.pathname === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (url.pathname === `${base}/ai/analyses`) return ok({ roomId, analyses: [] })
    return route.fallback()
  })
  return state
}

test('진행 중인 이해도 체크는 응답 이벤트로 인원·비율이 갱신되고 미응답은 따로 보인다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: understanding(501, 'OPEN'), results: { 501: results(501, 'OPEN', [0, 0, 0], 20) },
  })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(card(page, 1)).toBeVisible()
  await expect(card(page, 1).getByText('진행 중')).toBeVisible()
  await expect(total(page, 1, '미응답')).toContainText('20명')

  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 20, respondedParticipants: 12, unrespondedParticipants: 8,
    counts: { 1: 6, 2: 4, 3: 2 } } })

  await expect(option(page, 1, '이해됨')).toContainText('6명')
  await expect(option(page, 1, '이해됨')).toContainText('응답자의 50.0% · 전체의 30.0%')
  await expect(option(page, 1, '모르겠음')).toContainText('2명')
  await expect(total(page, 1, '응답')).toContainText('12명 (60.0%)')
  await expect(total(page, 1, '미응답')).toContainText('8명')
})

test('다른 투표의 결과 이벤트는 무시하고 마감되면 최종 결과를 다시 받는다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: understanding(501, 'OPEN'), results: { 501: results(501, 'OPEN', [1, 0, 0], 10) },
  })
  await page.goto(`/host/rooms/${roomId}`)
  await expect(card(page, 1)).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic) && state.subscribed.has(resultsTopic)).toBe(true)

  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 999, totalParticipants: 10, respondedParticipants: 9, unrespondedParticipants: 1, counts: { 1: 9 } } })
  await expect(option(page, 1, '이해됨')).toContainText('1명')

  state.open = null
  state.closed = [understanding(501, 'CLOSED')]
  state.results[501] = results(501, 'CLOSED', [5, 3, 1], 12)
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 501, roomId, closeReason: 'TIMEOUT' } })

  await expect(card(page, 1).getByText('마감', { exact: true })).toBeVisible()
  await expect(option(page, 1, '이해됨')).toContainText('5명')
  await expect(total(page, 1, '미응답')).toContainText('3명')
})

test('마감된 체크를 포함해 모든 이해도 체크를 최신순으로 보여 주고 마감 결과는 한 번만 받는다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: understanding(503, 'OPEN'),
    closed: [understanding(501, 'CLOSED'), understanding(502, 'CLOSED')],
    results: { 501: results(501, 'CLOSED', [2, 2, 2], 6), 502: results(502, 'CLOSED', [4, 1, 0], 6),
      503: results(503, 'OPEN', [1, 0, 0], 6) },
  })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(summary(page).getByText('총 3회')).toBeVisible()
  // 위에서부터 진행 중인 3회차, 2회차, 1회차 순서다.
  await expect(summary(page).getByRole('listitem', { name: /^이해도 체크 \d$/ })).toHaveText([/3회차/, /2회차/, /1회차/])
  await expect(card(page, 3).getByText('진행 중')).toBeVisible()
  await expect(card(page, 2).getByText('마감', { exact: true })).toBeVisible()
  await expect(option(page, 1, '헷갈림')).toContainText('2명')
  await expect(option(page, 2, '이해됨')).toContainText('4명')

  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 503, totalParticipants: 6, respondedParticipants: 3, unrespondedParticipants: 3, counts: { 1: 2, 2: 1, 3: 0 } } })
  await expect(option(page, 3, '이해됨')).toContainText('2명')
  // 지난 체크는 그대로이고 마감 결과는 다시 받지 않는다.
  await expect(option(page, 2, '이해됨')).toContainText('4명')
  expect(state.resultReads.filter(id => id === 501)).toHaveLength(1)
})

test('체크가 많아도 목록 영역만 스크롤되어 오래된 체크까지 볼 수 있다', async ({ page, context }) => {
  const ids = Array.from({ length: 12 }, (_, index) => 600 + index)
  const state = await setup(page, context, {
    closed: ids.map(id => understanding(id, 'CLOSED')),
    results: Object.fromEntries(ids.map(id => [id, results(id, 'CLOSED', [id - 599, 0, 0], 20)])),
  })
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto(`/host/rooms/${roomId}`)

  const list = summary(page).getByRole('list', { name: '이해도 체크 목록' })
  await expect(summary(page).getByText('총 12회')).toBeVisible()
  const sizes = await list.evaluate(node => ({ scroll: node.scrollHeight, client: node.clientHeight }))
  expect(sizes.scroll).toBeGreaterThan(sizes.client)
  expect(sizes.client).toBeLessThanOrEqual(420)

  // 브라우저 창이 아니라 스크롤되는 목록 영역 안에서 보이는지를 비교한다.
  const shown = number => card(page, number).evaluate(node => {
    const area = node.parentElement.getBoundingClientRect()
    const box = node.getBoundingClientRect()
    return box.bottom > area.top && box.top < area.bottom
  })
  await expect.poll(() => shown(12)).toBe(true)
  await expect.poll(() => shown(1)).toBe(false)
  await list.evaluate(node => { node.scrollTop = node.scrollHeight })
  await expect.poll(() => shown(1)).toBe(true)
  await expect.poll(() => shown(12)).toBe(false)
  await expect(option(page, 1, '이해됨')).toContainText('1명')
  expect([...state.resultReads].sort()).toEqual(ids)
})

test('이해도 체크가 한 번도 없으면 결과 패널을 보이지 않는다', async ({ page, context }) => {
  const state = await setup(page, context)
  await page.goto(`/host/rooms/${roomId}`)
  await expect(page.getByRole('heading', { name: '이해도 테스트 강연' })).toBeVisible()
  await expect.poll(() => state.subscribed.has(resultsTopic)).toBe(true)
  await expect(summary(page)).toHaveCount(0)
})

test('마감된 이해도 체크에 늦게 도착한 응답 이벤트는 확정된 결과를 덮어쓰지 않는다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: understanding(501, 'OPEN'), results: { 501: results(501, 'OPEN', [1, 0, 0], 10) },
  })
  await page.goto(`/host/rooms/${roomId}`)
  await expect(card(page, 1).getByText('진행 중')).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic) && state.subscribed.has(resultsTopic)).toBe(true)

  state.open = null
  state.closed = [understanding(501, 'CLOSED')]
  state.results[501] = results(501, 'CLOSED', [5, 3, 1], 12)
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 501, roomId, closeReason: 'HOST' } })
  await expect(card(page, 1).getByText('마감', { exact: true })).toBeVisible()
  await expect(option(page, 1, '이해됨')).toContainText('5명')

  // 다음 체크가 열린 뒤 늦은 이벤트와 새 체크의 이벤트를 차례로 보낸다. 새 체크가 반영되면 앞선 늦은 이벤트도 처리된 것이다.
  state.open = understanding(502, 'OPEN')
  state.results[502] = results(502, 'OPEN', [0, 0, 0], 12)
  state.push[pollsTopic]({ type: 'POLL_CREATED', data: understanding(502, 'OPEN') })
  await expect(card(page, 2)).toBeVisible()
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 501, totalParticipants: 12, respondedParticipants: 1, unrespondedParticipants: 11, counts: { 1: 1 } } })
  state.push[resultsTopic]({ type: 'POLL_RESULTS_UPDATED', data: {
    pollId: 502, totalParticipants: 12, respondedParticipants: 4, unrespondedParticipants: 8, counts: { 1: 4 } } })
  await expect(option(page, 2, '이해됨')).toContainText('4명')
  await expect(option(page, 1, '이해됨')).toContainText('5명')
  await expect(total(page, 1, '미응답')).toContainText('3명')
})
