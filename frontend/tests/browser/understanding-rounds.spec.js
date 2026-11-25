import { expect, test } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

const roomId = 10
const base = `/api/v1/rooms/${roomId}`
const pollsTopic = `/topic/rooms/${roomId}/polls`
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
// 서버(POLL-06)가 계산해서 내려주는 라운드. 헷갈림+모르겠음이 3명 이상이고 응답자의 40% 이상이면 이해 부족이다.
const round = (pollId, openedAt, [understood, confused, notUnderstood], total) => {
  const responded = understood + confused + notUnderstood
  const lack = confused + notUnderstood
  const ratio = responded ? Math.round(lack * 1000 / responded) / 10 : 0
  return { pollId, openedAt, closedAt: openedAt, totalParticipants: total, respondedParticipants: responded,
    counts: { UNDERSTOOD: understood, CONFUSED: confused, NOT_UNDERSTOOD: notUnderstood },
    lackCount: lack, lackRatio: ratio, lowUnderstanding: lack >= 3 && ratio >= 40 }
}
const first = round(501, '2026-11-21T03:00:00Z', [8, 2, 1], 12)
const second = round(502, '2026-11-21T03:15:00Z', [3, 4, 3], 12)
const panel = page => page.getByRole('region', { name: '이해도 변화' })
const row = (page, label) => panel(page).getByRole('row').filter({ has: page.getByRole('rowheader', { name: new RegExp(`^${label}`) }) })

async function setup(page, context, { open = null, closed = [], rounds = [] } = {}) {
  await setupRecording(context)
  const state = { open, closed, rounds, roundReads: 0, roundsFail: false, push: {}, subscribed: new Set() }
  await context.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      const message = frame.toString()
      if (message.startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      const destination = /^destination:(.+)$/m.exec(message)?.[1]
      if (message.startsWith('SUBSCRIBE\n') && destination) {
        const id = /^id:(.+)$/m.exec(message)[1]
        state.subscribed.add(destination)
        state.push[destination] = event => socket.send(`MESSAGE\ndestination:${destination}\nsubscription:${id}\nmessage-id:${crypto.randomUUID()}\n\n${JSON.stringify(event)}\0`)
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
    if (url.pathname === `${base}/polls/understanding-rounds`) {
      state.roundReads += 1
      if (state.roundsFail) return route.fulfill({ status: 500, json: { code: 'INTERNAL_ERROR' } })
      return ok({ roomId, ratio: 40, minCount: 3, rounds: state.rounds })
    }
    const read = /\/polls\/(\d+)\/results$/.exec(url.pathname)
    if (read) {
      const id = Number(read[1])
      const status = state.closed.some(poll => poll.pollId === id) ? 'CLOSED' : 'OPEN'
      return ok(results(id, status, [5, 3, 1], 12))
    }
    if (url.pathname === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (url.pathname === `${base}/ai/analyses`) return ok({ roomId, analyses: [] })
    return route.fallback()
  })
  return state
}

test('마감된 체크가 없으면 그래프를 숨기고 라운드 조회도 하지 않는다', async ({ page, context }) => {
  const state = await setup(page, context, { open: understanding(501, 'OPEN') })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(page.getByRole('region', { name: '이해도 체크 결과' })).toBeVisible()
  await expect(panel(page)).toHaveCount(0)
  expect(state.roundReads).toBe(0)
})

test('마감된 라운드를 막대 그래프와 표로 보여 주고 기준을 넘은 라운드만 이해 부족으로 표시한다', async ({ page, context }) => {
  const state = await setup(page, context, {
    closed: [understanding(502, 'CLOSED'), understanding(501, 'CLOSED')], rounds: [first, second],
  })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(panel(page)).toBeVisible()
  await expect(panel(page).getByText('마감된 2개 라운드')).toBeVisible()
  await expect(panel(page).getByRole('img', { name: /2회에서 이해 부족 기준\(40%\)을 넘었습니다/ })).toBeVisible()
  await expect(panel(page).getByText('이해 부족 기준 40%')).toBeVisible()

  await expect(row(page, '1회')).toContainText('11/12명')
  await expect(row(page, '1회')).toContainText('27.3%')
  await expect(row(page, '1회').getByText('이해 부족')).toHaveCount(0)
  await expect(row(page, '2회')).toContainText('10/12명')
  await expect(row(page, '2회')).toContainText('70.0%')
  await expect(row(page, '2회').getByText('이해 부족')).toBeVisible()
  await expect(panel(page).getByRole('row').filter({ hasText: '12:15' })).toHaveCount(1)
  expect(state.roundReads).toBe(1)
})

test('새 체크가 마감되면 라운드를 다시 받아 그래프에 더한다', async ({ page, context }) => {
  const state = await setup(page, context, {
    open: understanding(502, 'OPEN'), closed: [understanding(501, 'CLOSED')], rounds: [first],
  })
  await page.goto(`/host/rooms/${roomId}`)
  await expect(panel(page).getByText('마감된 1개 라운드')).toBeVisible()
  await expect.poll(() => state.subscribed.has(pollsTopic)).toBe(true)

  state.open = null
  state.closed = [understanding(502, 'CLOSED'), understanding(501, 'CLOSED')]
  state.rounds = [first, second]
  state.push[pollsTopic]({ type: 'POLL_CLOSED', data: { pollId: 502, roomId, closeReason: 'HOST' } })

  await expect(panel(page).getByText('마감된 2개 라운드')).toBeVisible()
  await expect(row(page, '2회').getByText('이해 부족')).toBeVisible()
  expect(state.roundReads).toBe(2)
})

test('라운드 조회가 실패해도 이해도 체크 결과는 그대로 보이고 다시 시도하면 복구된다', async ({ page, context }) => {
  const state = await setup(page, context, { closed: [understanding(501, 'CLOSED')], rounds: [first] })
  state.roundsFail = true
  await page.goto(`/host/rooms/${roomId}`)

  await expect(panel(page).getByRole('alert')).toContainText('라운드별 변화를 불러오지 못했어요')
  await expect(page.getByRole('region', { name: '이해도 체크 결과' })).toBeVisible()
  await expect(panel(page).getByRole('table')).toHaveCount(0)

  state.roundsFail = false
  await panel(page).getByRole('button', { name: '다시 시도' }).click()
  await expect(panel(page).getByRole('alert')).toHaveCount(0)
  await expect(row(page, '1회')).toContainText('27.3%')
})
