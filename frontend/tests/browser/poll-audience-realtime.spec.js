import { expect, test } from '@playwright/test'

const roomId = 42
const base = `/api/v1/rooms/${roomId}`
const topic = `/topic/rooms/${roomId}/polls`

function generalPoll(pollId, question) {
  const now = new Date()
  return {
    pollId, roomId, type: 'GENERAL', question, status: 'OPEN',
    options: ['Java', 'SQL'].map((content, index) => ({ optionId: pollId * 10 + index, content, displayOrder: index + 1, understandingStatus: null })),
    durationSeconds: 120, createdAt: now.toISOString(), closesAt: new Date(now.getTime() + 120000).toISOString(), serverTime: now.toISOString(),
  }
}

// 브라우저 시계를 멈춰 5초 조회가 돌지 않게 한다. 화면이 바뀌면 WebSocket 이벤트 때문이다.
async function setup(page, context, { poll }) {
  await page.clock.install()
  const state = { poll, pushers: [], subscriptions: 0, listReads: 0 }
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const ok = data => route.fulfill({ status: 200, json: { data } })
    if (path === '/api/v1/auth/csrf') return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf-token' })
    if (path === '/api/v1/public/rooms/WORKSHOP') return ok({ roomId, title: '실시간 투표 강연', status: 'LIVE', canJoin: true })
    if (path === `${base}/participants/me`) return ok({ roomId, participantId: 7, nickname: '청중', joinedAt: '2026-11-21T01:00:00Z' })
    if (path === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (path === `${base}/polls`) {
      state.listReads++
      return ok({ polls: state.poll ? [state.poll] : [], serverTime: new Date().toISOString() })
    }
    if (/\/polls\/\d+\/responses\/me$/.test(path)) return ok({ pollId: state.poll?.pollId, participantId: 7, optionId: null, respondedAt: null })
    throw new Error(`예상하지 않은 API 호출: ${request.method()} ${path}`)
  })
  await context.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      const message = frame.toString()
      if (message.startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      if (message.startsWith('SUBSCRIBE\n') && message.includes(`destination:${topic}\n`)) {
        const id = /^id:(.+)$/m.exec(message)[1]
        state.subscriptions++
        state.pushers.push(event => socket.send(`MESSAGE\ndestination:${topic}\nsubscription:${id}\nmessage-id:${crypto.randomUUID()}\n\n${JSON.stringify(event)}\0`))
      }
    })
  })
  state.push = event => state.pushers.forEach(push => push(event))
  await page.goto('/join/WORKSHOP')
  // 투표 패널과 이해도 체크가 구독 하나를 나눠 쓴다.
  await expect.poll(() => state.subscriptions).toBeGreaterThanOrEqual(1)
  return state
}

const panel = page => page.locator('.participation-poll')

test('강연자가 투표를 마감하면 청중 화면에서 바로 사라진다', async ({ page, context }) => {
  const state = await setup(page, context, { poll: generalPoll(51, '가장 쓰는 언어는?') })
  await expect(panel(page).getByText('가장 쓰는 언어는?')).toBeVisible()

  state.poll = null
  state.push({ type: 'POLL_CLOSED', data: { pollId: 51, roomId, closedAt: new Date().toISOString(), closeReason: 'HOST' } })

  await expect(panel(page).getByText('현재 진행 중인 투표가 없습니다.')).toBeVisible()
  await expect(panel(page).getByText('가장 쓰는 언어는?')).toHaveCount(0)
})

test('강연자가 투표를 열면 청중 화면에 바로 나타난다', async ({ page, context }) => {
  const state = await setup(page, context, { poll: null })
  await expect(panel(page).getByText('현재 진행 중인 투표가 없습니다.')).toBeVisible()

  state.poll = generalPoll(52, '새 투표입니다')
  state.push({ type: 'POLL_CREATED', data: { ...state.poll } })

  await expect(panel(page).getByText('새 투표입니다')).toBeVisible()
})

test('다른 강연방의 이벤트는 무시한다', async ({ page, context }) => {
  const state = await setup(page, context, { poll: generalPoll(53, '남아 있어야 하는 투표') })
  await expect(panel(page).getByText('남아 있어야 하는 투표')).toBeVisible()
  const reads = state.listReads

  state.push({ type: 'POLL_CLOSED', data: { pollId: 53, roomId: 999, closedAt: new Date().toISOString(), closeReason: 'HOST' } })
  // 다른 방의 이벤트 뒤에 이 방의 이벤트를 보내, 앞선 이벤트가 이미 처리됐음을 확인한다.
  state.poll = generalPoll(54, '다음 투표')
  state.push({ type: 'POLL_CREATED', data: { ...state.poll } })

  await expect(panel(page).getByText('다음 투표')).toBeVisible()
  // 조회는 이 방의 이벤트 한 번뿐이다.
  expect(state.listReads - reads).toBe(1)
})

test('이벤트가 없으면 5초마다 투표 목록을 조회하지 않고 30초 주기 조회로 상태를 다시 맞춘다', async ({ page, context }) => {
  const state = await setup(page, context, { poll: generalPoll(55, '가장 쓰는 언어는?') })
  await expect(panel(page).getByText('가장 쓰는 언어는?')).toBeVisible()
  const reads = state.listReads

  // 5초 조회가 남아 있었다면 15초 동안 세 번 이상 조회한다.
  await page.clock.runFor(15000)
  expect(state.listReads).toBe(reads)

  // 이벤트를 놓쳤어도 소켓의 30초 주기 조회가 서버 상태를 다시 가져온다.
  state.poll = null
  await page.clock.runFor(20000)
  await expect(panel(page).getByText('현재 진행 중인 투표가 없습니다.')).toBeVisible()
  expect(state.listReads).toBeGreaterThan(reads)
})
