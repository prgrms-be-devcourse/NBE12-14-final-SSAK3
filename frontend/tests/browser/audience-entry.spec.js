import { expect, test } from '@playwright/test'

const invitation = '/join/WORKSHOP'
const roomId = 42
const topic = '/topic/rooms/42/status'
const participant = { roomId, participantId: 7, nickname: '다은', joinedAt: '2026-11-21T01:00:00Z' }
const waiting = page => page.getByRole('heading', { name: '강연 시작 대기 중' })
const live = page => page.getByRole('heading', { name: '강연 진행 중' })
const retryJoin = page => page.getByRole('button', { name: '입장 다시 시도' })

async function setup(page) {
  const state = { status: 'READY', hasSession: false, joinResult: 'created', posts: [], subscriptions: [] }
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const ok = (data, status = 200) => route.fulfill({ status, json: { data } })
    const fail = (status, code) => route.fulfill({ status, json: { code } })
    if (path === '/api/v1/auth/csrf') return ok({ headerName: 'X-CSRF-TOKEN', token: 'public-token' })
    if (path === '/api/v1/public/rooms/WORKSHOP') {
      if (state.status === 'deleted') return fail(404, 'INVALID_JOIN_CODE')
      return ok({ roomId, title: '오후 워크숍', status: state.status, canJoin: state.status !== 'ENDED' })
    }
    if (path === '/api/v1/rooms/42/participants/me') {
      return state.hasSession ? ok(participant) : fail(401, 'UNAUTHENTICATED')
    }
    if (path === '/api/v1/public/rooms/WORKSHOP/participants' && request.method() === 'POST') {
      expect(request.headers()['x-csrf-token']).toBe('public-token')
      state.posts.push({ key: request.headers()['idempotency-key'], ...request.postDataJSON() })
      if (state.joinResult === 'lost') return route.abort()
      if (state.joinResult === 'expired') return fail(409, 'CONFLICT')
      state.hasSession = true
      return ok({ ...participant, roomStatus: state.status }, 201)
    }
    throw new Error(`예상하지 않은 청중 API 호출: ${request.method()} ${path}`)
  })
  await page.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      const message = frame.toString()
      if (message.startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      if (message.startsWith('SUBSCRIBE\n')) {
        expect(message).toContain(`destination:${topic}\n`)
        const id = /^id:(.+)$/m.exec(message)[1]
        state.subscriptions.push({
          close: () => socket.close({ code: 4001, reason: 'test disconnect' }),
          send: event => socket.send(`MESSAGE\ndestination:${topic}\nsubscription:${id}\nmessage-id:${crypto.randomUUID()}\n\n${JSON.stringify(event)}\0`),
        })
      }
    })
  })
  return state
}

async function submitNickname(page) {
  await page.getByLabel('닉네임').fill(participant.nickname)
  await page.getByRole('button', { name: '입장', exact: true }).click()
}

async function waitForConnection(page, state) {
  await expect.poll(() => state.subscriptions.length).toBeGreaterThan(0)
  await expect(page.getByText('연결 확인 중', { exact: true })).toHaveCount(0)
  return state.subscriptions.at(-1)
}

test('입장 응답 유실 시 새로고침 후 같은 키로 재시도하고, 키가 만료되면 새로 입장할 때만 교체한다', async ({ page }) => {
  const state = await setup(page)
  state.joinResult = 'lost'
  await page.goto(invitation)
  await submitNickname(page)
  await expect(retryJoin(page)).toBeVisible()
  const first = state.posts[0]
  expect(first.key).toMatch(/^[0-9a-f-]{36}$/i)
  expect(first.nickname).toBe('다은')

  await page.reload()
  await expect(retryJoin(page)).toBeVisible()
  await expect(page.getByLabel('닉네임')).toHaveValue('다은')
  expect(state.posts).toHaveLength(1)
  state.joinResult = 'expired'
  await retryJoin(page).click()
  const joinAgain = page.getByRole('button', { name: '새로 입장' })
  await expect(joinAgain).toBeVisible()
  expect(state.posts[1]).toEqual(first)

  await page.reload()
  await expect(joinAgain).toBeVisible()
  await expect(page.getByLabel('닉네임')).toHaveValue('다은')
  expect(state.posts).toHaveLength(2)
  state.status = 'LIVE'
  state.joinResult = 'created'
  await joinAgain.click()
  await expect(live(page)).toBeVisible()
  expect(state.posts).toHaveLength(3)
  expect(state.posts[2].key).toMatch(/^[0-9a-f-]{36}$/i)
  expect(state.posts[2].key).not.toBe(first.key)
})

test('응답을 놓친 뒤 유효한 입장 세션이 확인되면 새 요청 없이 대기 화면으로 복귀한다', async ({ page }) => {
  const state = await setup(page)
  state.joinResult = 'lost'
  await page.goto(invitation)
  await submitNickname(page)
  await expect(retryJoin(page)).toBeVisible()
  state.hasSession = true
  await page.reload()
  await expect(waiting(page)).toBeVisible()
  await expect(page.getByText('다은님으로 입장했습니다.')).toBeVisible()
  await expect(page.getByLabel('닉네임')).toHaveCount(0)
  expect(state.posts).toHaveLength(1)
})

test('다른 방 이벤트는 건너뛰고 내 방의 시작·정상 종료 알림만 화면에 반영한다', async ({ page }) => {
  const state = await setup(page)
  await page.goto(invitation)
  await submitNickname(page)
  await expect(waiting(page)).toBeVisible()
  const socket = await waitForConnection(page, state)
  socket.send({ type: 'ROOM_DELETED', data: { roomId: roomId + 1 } })
  socket.send({ type: 'ROOM_STATUS_CHANGED', data: { roomId, status: 'LIVE' } })
  await expect(live(page)).toBeVisible()
  await expect(page).toHaveURL(/\/join\/WORKSHOP$/)
  socket.send({ type: 'ROOM_STATUS_CHANGED', data: { roomId, status: 'ENDED' } })
  await expect(page).toHaveURL(/\/participation-ended\/ended$/)
  await expect(page.getByRole('alert')).toHaveText('강연이 종료되었습니다')
})

for (const [status, reason, notice] of [
  ['deleted', 'unavailable', '더 이상 참여할 수 없는 강연방입니다'],
  ['ENDED', 'ended', '강연이 종료되었습니다'],
]) {
  test(`대기 중 연결이 끊기면 화면을 유지하고 재연결 후 서버의 ${status} 상태로 퇴장한다`, async ({ page }) => {
    const state = await setup(page)
    state.hasSession = true
    await page.goto(invitation)
    await expect(waiting(page)).toBeVisible()
    const socket = await waitForConnection(page, state)
    await socket.close()
    await expect(page.getByRole('status')).toHaveText('연결 확인 중')
    await expect(waiting(page)).toBeVisible()
    state.status = status
    await expect(page).toHaveURL(new RegExp(`/participation-ended/${reason}$`), { timeout: 10000 })
    await expect(page.getByRole('alert')).toHaveText(notice)
    await page.reload()
    await expect(page.getByRole('heading', { name: '참여 종료' })).toBeVisible()
    await expect(page.getByRole('alert')).toHaveText(notice)
    expect(state.posts).toHaveLength(0)
  })
}
