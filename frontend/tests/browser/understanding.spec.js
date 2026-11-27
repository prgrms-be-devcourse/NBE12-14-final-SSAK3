import { expect, test } from '@playwright/test'

const roomId = 42
const topic = '/topic/rooms/42/polls'
const options = [
  { optionId: 1, content: '이해됨', displayOrder: 1, understandingStatus: 'UNDERSTOOD' },
  { optionId: 2, content: '헷갈림', displayOrder: 2, understandingStatus: 'CONFUSED' },
  { optionId: 3, content: '모르겠음', displayOrder: 3, understandingStatus: 'NOT_UNDERSTOOD' },
]
const understanding = {
  pollId: 501, roomId, type: 'UNDERSTANDING', question: '지금 설명을 이해하셨나요?', status: 'OPEN',
  options, closesAt: '2026-11-21T03:01:00Z', serverTime: '2026-11-21T03:00:00Z',
}
const card = page => page.getByRole('region', { name: '지금 설명을 이해하셨나요?' })
const ended = page => page.getByText('이해도 체크가 마감되었어요.')

async function setup(page, { polls = [], mine = null } = {}) {
  // 같은 토픽의 구독. 한 연결에 구독이 여러 개 실릴 수 있어 연결과 구독 id 의 쌍으로 모은다.
  const subscribers = []
  const sockets = new Set()
  const state = {
    polls, mine, puts: [], putFailure: null,
    push(event) {
      for (const { socket, id } of subscribers) {
        socket.send(`MESSAGE\ndestination:${topic}\nsubscription:${id}\nmessage-id:${crypto.randomUUID()}\n\n${JSON.stringify(event)}\0`)
      }
    },
  }
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const ok = data => route.fulfill({ json: { data } })
    if (path === '/api/v1/auth/csrf') return ok({ headerName: 'X-CSRF-TOKEN', token: 'poll-token' })
    if (path === '/api/v1/public/rooms/WORKSHOP') return ok({ roomId, title: '오후 워크숍', status: 'LIVE', canJoin: true })
    if (path === '/api/v1/rooms/42/participants/me') {
      return ok({ roomId, participantId: 7, nickname: '다은', joinedAt: '2026-11-21T02:00:00Z' })
    }
    if (path === '/api/v1/rooms/42/messages') return ok({ messages: [], nextCursor: null, hasNext: false })
    if (path === '/api/v1/rooms/42/polls') return ok({ polls: state.polls, serverTime: '2026-11-21T03:00:00Z' })
    if (path === '/api/v1/rooms/42/polls/501/responses/me') {
      if (request.method() === 'GET') return ok({ pollId: 501, participantId: 7, optionId: state.mine, respondedAt: null })
      expect(request.headers()['x-csrf-token']).toBe('poll-token')
      const body = request.postDataJSON()
      state.puts.push(body.optionId)
      if (state.putFailure) return route.fulfill(state.putFailure)
      state.mine = body.optionId
      return ok({ pollId: 501, participantId: 7, optionId: body.optionId, changed: true })
    }
    throw new Error(`예상하지 않은 API 호출: ${request.method()} ${path}`)
  })
  await page.routeWebSocket('**/ws', socket => {
    sockets.add(socket)
    socket.onClose(() => {
      sockets.delete(socket)
      for (let i = subscribers.length - 1; i >= 0; i--) if (subscribers[i].socket === socket) subscribers.splice(i, 1)
    })
    socket.onMessage(frame => {
      const message = frame.toString()
      if (message.startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      if (message.startsWith('SUBSCRIBE\n') && message.includes(`destination:${topic}\n`)) {
        const id = /^id:(.+)$/m.exec(message)[1]
        subscribers.push({ socket, id })
      }
    })
  })
  await page.goto('/join/WORKSHOP')
  // 일반 투표와 이해도 체크는 같은 토픽의 구독 하나를 나눠 쓰고, 연결도 청중 화면 전체가 하나만 쓴다.
  await expect.poll(() => subscribers.length).toBe(1)
  expect(sockets.size).toBe(1)
  return state
}

test('이해도 체크가 열리면 버튼이 나타나고 마감 전에는 선택을 바꿀 수 있다', async ({ page }) => {
  const state = await setup(page)
  await expect(card(page)).toHaveCount(0)

  state.push({ type: 'POLL_CREATED', data: understanding })
  await expect(card(page)).toBeVisible()
  await expect(card(page).getByRole('button')).toHaveText(['이해됨', '헷갈림', '모르겠음'])

  await card(page).getByRole('button', { name: '헷갈림' }).click()
  await expect(card(page).getByRole('button', { name: '헷갈림' })).toHaveAttribute('aria-pressed', 'true')
  await card(page).getByRole('button', { name: '이해됨' }).click()
  await expect(card(page).getByRole('button', { name: '이해됨' })).toHaveAttribute('aria-pressed', 'true')
  await expect(card(page).getByRole('button', { name: '헷갈림' })).toHaveAttribute('aria-pressed', 'false')
  expect(state.puts).toEqual([2, 1])

  state.push({ type: 'POLL_CLOSED', data: { pollId: 501, roomId, closedAt: '2026-11-21T03:00:40Z', closeReason: 'HOST' } })
  await expect(ended(page)).toBeVisible()
  await expect(page.getByRole('button', { name: '이해됨' })).toHaveCount(0)
})

test('다른 투표의 마감 이벤트와 일반 투표 생성은 이해도 카드에 영향을 주지 않는다', async ({ page }) => {
  const state = await setup(page)
  state.push({ type: 'POLL_CREATED', data: { ...understanding, pollId: 502, type: 'GENERAL', question: '일반 투표' } })
  state.push({ type: 'POLL_CREATED', data: understanding })
  await expect(card(page)).toBeVisible()
  state.push({ type: 'POLL_CLOSED', data: { pollId: 999, roomId } })
  await expect(card(page)).toBeVisible()
  await expect(page.getByText('일반 투표')).toHaveCount(0)
})

test('새로고침한 청중은 진행 중인 이해도 체크와 내 선택을 복원한다', async ({ page }) => {
  await setup(page, { polls: [{ ...understanding, createdAt: '2026-11-21T03:00:00Z' }], mine: 2 })
  await expect(card(page)).toBeVisible()
  await expect(card(page).getByRole('button', { name: '헷갈림' })).toHaveAttribute('aria-pressed', 'true')
  await expect(card(page).getByRole('button', { name: '이해됨' })).toHaveAttribute('aria-pressed', 'false')
})

test('서버가 이미 마감됐다고 거부하면 선택을 반영하지 않고 마감 안내로 바뀐다', async ({ page }) => {
  const state = await setup(page)
  state.push({ type: 'POLL_CREATED', data: understanding })
  await expect(card(page)).toBeVisible()

  state.putFailure = { status: 409, json: { code: 'POLL_CLOSED' } }
  state.polls = []
  await card(page).getByRole('button', { name: '모르겠음' }).click()
  await expect(ended(page)).toBeVisible()
  expect(state.puts).toEqual([3])
})

test('전송에 실패하면 오류를 알리고 이전 선택을 그대로 둔다', async ({ page }) => {
  const state = await setup(page, { polls: [{ ...understanding, createdAt: '2026-11-21T03:00:00Z' }], mine: 1 })
  await expect(card(page).getByRole('button', { name: '이해됨' })).toHaveAttribute('aria-pressed', 'true')

  state.putFailure = { status: 503, json: { code: 'INTERNAL_ERROR' } }
  await card(page).getByRole('button', { name: '헷갈림' }).click()
  await expect(card(page).getByRole('alert')).toHaveText('전송하지 못했어요. 다시 눌러 주세요.')
  await expect(card(page).getByRole('button', { name: '이해됨' })).toHaveAttribute('aria-pressed', 'true')
  await expect(card(page).getByRole('button', { name: '헷갈림' })).toHaveAttribute('aria-pressed', 'false')

  state.putFailure = null
  await card(page).getByRole('button', { name: '헷갈림' }).click()
  await expect(card(page).getByRole('button', { name: '헷갈림' })).toHaveAttribute('aria-pressed', 'true')
  await expect(card(page).getByRole('alert')).toHaveCount(0)
})
