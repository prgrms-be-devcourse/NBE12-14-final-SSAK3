import { expect, test } from '@playwright/test'

const roomId = 42
const base = `/api/v1/rooms/${roomId}`
const topics = {
  status: `/topic/rooms/${roomId}/status`,
  chat: `/topic/rooms/${roomId}/chat`,
  polls: `/topic/rooms/${roomId}/polls`,
}

function generalPoll(pollId, question) {
  const now = new Date()
  return {
    pollId, roomId, type: 'GENERAL', question, status: 'OPEN',
    options: ['Java', 'SQL'].map((content, index) => ({ optionId: pollId * 10 + index, content, displayOrder: index + 1, understandingStatus: null })),
    durationSeconds: 120, createdAt: now.toISOString(), closesAt: new Date(now.getTime() + 120000).toISOString(), serverTime: now.toISOString(),
  }
}

// 청중 화면(상태·채팅·투표·이해도 체크)의 WebSocket 연결과 구독을 모은다.
async function setup(page, context) {
  const state = {
    poll: generalPoll(51, '가장 쓰는 언어는?'),
    // 연결 순서대로 { socket, subs: [{ id, destination }] }
    connections: [],
    push(destination, event) {
      for (const { socket, subs } of state.connections.filter(connection => !connection.closed)) {
        for (const sub of subs.filter(item => item.destination === destination)) {
          socket.send(`MESSAGE\ndestination:${destination}\nsubscription:${sub.id}\nmessage-id:${crypto.randomUUID()}\n\n${JSON.stringify(event)}\0`)
        }
      }
    },
  }
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const ok = data => route.fulfill({ status: 200, json: { data } })
    if (path === '/api/v1/auth/csrf') return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf-token' })
    if (path === '/api/v1/public/rooms/WORKSHOP') return ok({ roomId, title: '공유 연결 강연', status: 'LIVE', canJoin: true })
    if (path === `${base}/participants/me`) return ok({ roomId, participantId: 7, nickname: '청중', joinedAt: '2026-11-21T01:00:00Z' })
    if (path === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (path === `${base}/polls`) return ok({ polls: state.poll ? [state.poll] : [], serverTime: new Date().toISOString() })
    if (/\/polls\/\d+\/responses\/me$/.test(path)) return ok({ pollId: state.poll?.pollId, participantId: 7, optionId: null, respondedAt: null })
    throw new Error(`예상하지 않은 API 호출: ${request.method()} ${path}`)
  })
  await context.routeWebSocket('**/ws', socket => {
    const connection = { socket, subs: [], closed: false }
    state.connections.push(connection)
    socket.onClose(() => { connection.closed = true })
    socket.onMessage(frame => {
      const message = frame.toString()
      if (message.startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      if (message.startsWith('SUBSCRIBE\n')) {
        connection.subs.push({
          id: /^id:(.+)$/m.exec(message)[1],
          destination: /^destination:(.+)$/m.exec(message)[1],
        })
      }
    })
  })
  await page.goto('/join/WORKSHOP')
  return state
}

const destinationsOf = connection => connection.subs.map(sub => sub.destination).sort()

test('청중 화면은 상태·채팅·투표 구독을 WebSocket 연결 하나에 모두 싣는다', async ({ page, context }) => {
  const state = await setup(page, context)

  // 상태 1개, 채팅 1개, 투표 알림 1개, 채팅 오류 큐 1개. 투표 패널과 이해도 체크가 같은 투표 알림 구독을 나눠 쓴다.
  await expect.poll(() => state.connections[0]?.subs.length).toBeGreaterThanOrEqual(4)
  expect(state.connections).toHaveLength(1)
  expect(destinationsOf(state.connections[0])).toEqual([topics.chat, topics.polls, topics.status, '/user/queue/errors'].sort())
})

test('투표 알림 구독 하나로 투표 패널과 이해도 체크가 모두 이벤트를 받는다', async ({ page, context }) => {
  const state = await setup(page, context)
  await expect.poll(() => state.connections[0]?.subs.length).toBe(4)
  await expect(page.locator('.participation-poll').getByText('가장 쓰는 언어는?')).toBeVisible()

  state.push(topics.polls, {
    type: 'POLL_CREATED',
    data: {
      pollId: 61, roomId, type: 'UNDERSTANDING', status: 'OPEN', question: '지금 설명을 이해하셨나요?',
      options: [], durationSeconds: 120, createdAt: new Date().toISOString(),
      closesAt: new Date(Date.now() + 120000).toISOString(), serverTime: new Date().toISOString(),
    },
  })

  // 이해도 체크 카드가 이벤트로 열리면 같은 구독이 두 호출 모두에 전달된 것이다.
  await expect(page.getByRole('region', { name: '지금 설명을 이해하셨나요?' })).toBeVisible()
})

test('연결이 끊겼다 다시 맺어지면 한 연결에 모든 구독을 다시 싣고 이벤트도 받는다', async ({ page, context }) => {
  const state = await setup(page, context)
  await expect.poll(() => state.connections[0]?.subs.length).toBeGreaterThanOrEqual(4)
  const first = destinationsOf(state.connections[0])
  await expect(page.locator('.participation-poll').getByText('가장 쓰는 언어는?')).toBeVisible()

  state.connections[0].socket.close()

  // 재접속 간격은 3초로 시작한다. 다시 맺은 연결 하나에 처음과 같은 구독이 모두 실린다.
  await expect.poll(() => state.connections.length, { timeout: 15000 }).toBe(2)
  await expect.poll(() => state.connections[1].subs.length, { timeout: 15000 }).toBe(first.length)
  expect(destinationsOf(state.connections[1])).toEqual(first)

  state.poll = null
  state.push(topics.polls, { type: 'POLL_CLOSED', data: { pollId: 51, roomId, closedAt: new Date().toISOString(), closeReason: 'HOST' } })
  await expect(page.locator('.participation-poll').getByText('현재 진행 중인 투표가 없습니다.')).toBeVisible()
})
