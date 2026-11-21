import { expect, test } from '@playwright/test'

const roomId = 42
const topic = '/topic/rooms/42/chat'
const host = { type: 'HOST', id: 1, name: '강연자 하나' }
const guest = { type: 'PARTICIPANT', id: 7, name: '청중 둘' }
const message = (id, content, sender = host) => ({
  messageId: id, roomId, sender, content, receivedAt: '2026-11-21T03:00:00Z',
})

async function openChat(page, initial = []) {
  const state = { saved: [...initial], sent: [], reads: [], csrfReads: 0, blockHistory: false, chat: null }
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const ok = data => route.fulfill({ json: { data } })
    if (url.pathname.endsWith('/auth/csrf')) {
      state.csrfReads++
      return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf-token' })
    }
    if (url.pathname.endsWith('/public/rooms/TEST')) return ok({ roomId, title: '실시간 질문', status: 'LIVE' })
    if (url.pathname.endsWith('/participants/me')) return ok({ roomId, participantId: guest.id, nickname: guest.name, joinedAt: '2026-11-21T02:00:00Z' })
    if (url.pathname.endsWith('/messages')) {
      expect(route.request().headers()['x-chat-role']).toBe('PARTICIPANT')
      if (state.blockHistory) return route.fulfill({ status: 503, json: { code: 'INTERNAL_ERROR' } })
      const cursor = Number(url.searchParams.get('cursor')) || Infinity
      state.reads.push(cursor)
      const earlier = state.saved.filter(item => item.messageId < cursor)
      const items = earlier.slice(-Number(url.searchParams.get('size') || 30))
      const hasNext = items.length < earlier.length
      return ok({ messages: items, hasNext, nextCursor: hasNext ? items[0].messageId : null })
    }
    throw Error('예상하지 않은 API 경로 ' + url.pathname)
  })
  await page.routeWebSocket('**/ws', socket => {
    const subscriptions = new Map()
    socket.onMessage(buffer => {
      const frame = String(buffer)
      const [header, body] = frame.split('\n\n')
      const [command, ...fields] = header.split('\n')
      const headers = Object.fromEntries(fields.map(field => {
        const i = field.indexOf(':')
        return [field.slice(0, i), field.slice(i + 1)]
      }))
      if (command === 'CONNECT') {
        expect(headers['X-CSRF-TOKEN']).toBe('test-csrf-token')
        expect(headers['X-Chat-Role']).toBe('PARTICIPANT')
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      }
      if (command === 'SUBSCRIBE') {
        subscriptions.set(headers.destination, headers.id)
        if (headers.destination !== topic) return
        expect(headers['X-Chat-Role']).toBe('PARTICIPANT')
        state.chat = {
          close: () => socket.close({ code: 4001, reason: '연결 끊김 테스트' }),
          broadcast: (item, attempt) => {
            const sentHeader = attempt ? `X-Chat-Attempt:${attempt}\n` : ''
            socket.send(`MESSAGE\ndestination:${topic}\nsubscription:${subscriptions.get(topic)}\nmessage-id:${item.messageId}\n${sentHeader}\n${JSON.stringify({ type: 'CHAT_CREATED', data: item })}\0`)
          },
        }
      }
      if (command === 'SEND') {
        expect(headers.destination).toBe('/app/rooms/42/chat')
        expect(headers['X-Chat-Role']).toBe('PARTICIPANT')
        state.sent.push({ attempt: headers['X-Chat-Attempt'], ...JSON.parse(body.replace(/\0$/, '')) })
      }
    })
  })
  await page.goto('/join/TEST')
  const panel = page.getByRole('region', { name: '강연 채팅' })
  await expect(panel).toBeVisible()
  await expect.poll(() => state.reads.length).toBeGreaterThan(0)
  await expect.poll(() => Boolean(state.chat)).toBe(true)
  return { state, panel }
}

test('STOMP CONNECT 전에 CSRF 토큰을 발급받고 청중 역할을 전달한다', async ({ page }) => {
  const { state } = await openChat(page)
  expect(state.csrfReads).toBeGreaterThan(0)
})

test('응답 유실 후 원문과 입력을 복구하고, 내역 갱신 후에만 수동 재전송한다', async ({ page }) => {
  const { state, panel } = await openChat(page)
  const input = panel.getByRole('textbox', { name: '메시지' })
  const content = '실습 자료를 다시 보여주세요.'
  await input.fill(content)
  await panel.getByRole('button', { name: '보내기', exact: true }).click()
  await expect.poll(() => state.sent.length).toBe(1)
  expect(state.sent[0].attempt).toBeTruthy()
  state.saved.push(message(1, content, guest))
  state.blockHistory = true
  state.chat.close()
  await expect(panel.getByText(/전송 여부를 확인하지 못했어요/)).toBeVisible()
  await expect(input).toHaveValue(content)
  await expect(panel.getByRole('button', { name: '다시 보내기' })).toBeDisabled()
  await expect(panel.getByRole('button', { name: '다시 불러오기' })).toBeVisible({ timeout: 10000 })
  state.blockHistory = false
  await panel.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(panel.getByRole('log').getByText(content, { exact: true })).toBeVisible()
  const retry = panel.getByRole('button', { name: '다시 보내기' })
  await expect(retry).toBeEnabled()
  expect(state.sent).toHaveLength(1)
  await retry.click()
  await expect.poll(() => state.sent.length).toBe(2)
  expect(state.sent[1].attempt).not.toBe(state.sent[0].attempt)
  // 첫 전송의 지연 알림이 새 전송의 입력을 지우면 안 된다.
  state.chat.broadcast(state.saved[0], state.sent[0].attempt)
  await expect(input).toHaveValue(content)
  state.chat.broadcast(message(2, content, guest), state.sent[1].attempt)
  await expect(input).toHaveValue('')
  await expect(panel.getByRole('log').getByText(content, { exact: true })).toHaveCount(2)
})

test('이전 질문을 읽는 중에는 위치를 유지하고 중복 없는 새 메시지 수를 표시한다', async ({ page }) => {
  const initial = Array.from({ length: 42 }, (_, i) => message(i + 1, `준비한 질문 ${i + 1}`))
  const { state, panel } = await openChat(page, initial)
  const history = panel.getByLabel('채팅 메시지', { exact: true })
  await expect(panel.getByText('준비한 질문 42', { exact: true })).toBeVisible()
  await expect.poll(() => history.evaluate(el => el.scrollHeight - el.clientHeight - el.scrollTop)).toBeLessThan(10)
  await history.evaluate(el => { el.scrollTop = 0 })
  await expect.poll(() => history.evaluate(el => el.scrollTop)).toBe(0)
  const next = message(43, '중간 질문')
  state.chat.broadcast(next)
  state.chat.broadcast(next)
  state.chat.broadcast(message(44, '마지막 질문'))
  const jump = panel.getByRole('button', { name: /새 메시지 2개/ })
  await expect(jump).toBeVisible()
  await expect(panel.getByText('중간 질문', { exact: true })).toHaveCount(1)
  await expect.poll(() => history.evaluate(el => el.scrollTop)).toBe(0)
  await jump.click()
  await expect(jump).toHaveCount(0)
  await expect.poll(() => history.evaluate(el => el.scrollHeight - el.clientHeight - el.scrollTop)).toBeLessThan(10)
})

// SCRUM-164: 최초 CSRF 발급 실패 후 WebSocket 연결 복구 검증
test('채팅 CONNECT는 첫 CSRF 503 이후 새로고침 없이 재시도한다', async ({ page }) => {
  let csrfReads = 0
  let openedSockets = 0
  let subscribed = false

  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/auth/csrf') {
      csrfReads++
      return route.fulfill({ status: csrfReads === 1 ? 503 : 200,
        json: csrfReads === 1 ? { code: 'INTERNAL_ERROR' }
            : { data: { headerName: 'X-CSRF-TOKEN', token: 'recovered' } } })
    }
    if (url.pathname === '/api/v1/auth/me') {
      return route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED' } })
    }
    if (url.pathname === '/api/v1/rooms/42/messages') {
      return route.fulfill({ json: { data: { messages: [], nextCursor: null, hasNext: false } } })
    }
    return route.fallback()
  })
  await page.routeWebSocket('**/ws', socket => {
    openedSockets++
    socket.onMessage(buffer => {
      const frame = String(buffer)
      if (frame.startsWith('CONNECT\n')) {
        expect(frame).toContain('X-CSRF-TOKEN:recovered')
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      }
      if (frame.startsWith('SUBSCRIBE\n') && frame.includes(`destination:${topic}`)) subscribed = true
    })
  })

  // 로그인 화면은 WebSocket을 만들지 않으므로 채팅 연결만 검증할 수 있다.
  await page.goto('/login')
  await page.evaluate(async () => {
    const { openRoomChat } = await import('/src/chat/roomChat.js')
    window.__retryChat = openRoomChat({
      roomId: 42, live: true, role: 'PARTICIPANT', since: '2026-11-21T00:00:00Z',
      onMessages() {}, onConnection() {}, onHistory() {}, onLost() {},
      onAck() {}, isCurrentAttempt: () => false, onError() {}, onAccessError() {},
    })
  })
  try {
    await expect.poll(() => csrfReads, { timeout: 15000 }).toBe(2)
    await expect.poll(() => subscribed, { timeout: 15000 }).toBe(true)
    expect(openedSockets).toBe(1)
  } finally {
    await page.evaluate(() => window.__retryChat?.close())
  }
})
