import { expect, test } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

const roomId = 10
const base = `/api/v1/rooms/${roomId}`
const options = [
  { optionId: 1, content: '이해됨', displayOrder: 1, understandingStatus: 'UNDERSTOOD' },
  { optionId: 2, content: '헷갈림', displayOrder: 2, understandingStatus: 'CONFUSED' },
  { optionId: 3, content: '모르겠음', displayOrder: 3, understandingStatus: 'NOT_UNDERSTOOD' },
]
const panel = page => page.locator('.room-poll')

async function setup(page, context, { open = null } = {}) {
  const recording = await setupRecording(context)
  await context.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      if (frame.toString().startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
    })
  })
  const state = { open, creates: [], closes: [], createStatus: 0, createCode: 'ROOM_NOT_LIVE' }
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()
    const ok = (data, status = 200) => route.fulfill({ status, json: { data } })
    if (url.pathname === base) {
      return ok({ roomId, title: '이해도 시작 강연', description: '', status: 'LIVE',
        createdAt: '2026-11-21T00:00:00Z', startedAt: '2026-11-21T01:00:00Z', endedAt: null })
    }
    if (url.pathname === `${base}/invitation`) {
      return ok({ joinCode: 'WORKSHOP', joinUrl: 'http://127.0.0.1:5174/join/WORKSHOP', qrText: 'http://127.0.0.1:5174/join/WORKSHOP' })
    }
    if (url.pathname === `${base}/polls` && method === 'GET') {
      const polls = url.searchParams.get('status') === 'OPEN' && state.open ? [state.open] : []
      return ok({ polls, serverTime: new Date().toISOString() })
    }
    if (url.pathname === `${base}/polls` && method === 'POST') {
      expect(request.headers()['x-csrf-token']).toBe(recording.csrfToken)
      const body = request.postDataJSON()
      state.creates.push(body)
      if (state.createStatus) return route.fulfill({ status: state.createStatus, json: { code: state.createCode } })
      const now = new Date()
      state.open = { pollId: 700, roomId, type: body.type, question: '지금 설명을 이해하셨나요?', status: 'OPEN',
        options, createdAt: now.toISOString(), closesAt: new Date(now.getTime() + body.durationSeconds * 1000).toISOString() }
      return ok(state.open, 201)
    }
    if (url.pathname === `${base}/polls/700/close` && method === 'POST') {
      expect(request.headers()['x-csrf-token']).toBe(recording.csrfToken)
      state.closes.push(700)
      state.open = null
      return ok({ pollId: 700, status: 'CLOSED', closedAt: new Date().toISOString(), closeReason: 'HOST' })
    }
    if (url.pathname === `${base}/polls/700/results`) {
      return ok({ pollId: 700, type: 'UNDERSTANDING', status: state.open ? 'OPEN' : 'CLOSED', closesAt: '2026-11-21T03:01:00Z',
        totalParticipants: 0, respondedParticipants: 0, unrespondedParticipants: 0, responseRate: 0,
        options: options.map(option => ({ ...option, count: 0, percentageOfResponses: 0, percentageOfAllParticipants: 0 })),
        calculatedAt: '2026-11-21T03:00:30Z' })
    }
    if (url.pathname === `${base}/messages`) return ok({ messages: [], nextCursor: null, hasNext: false })
    if (url.pathname === `${base}/ai/analyses`) return ok({ roomId, analyses: [] })
    return route.fallback()
  })
  return state
}

test('강연자는 이해도 체크를 제한 시간과 함께 시작하고 직접 마감할 수 있다', async ({ page, context }) => {
  const state = await setup(page, context)
  await page.goto(`/host/rooms/${roomId}`)

  await expect(panel(page).getByRole('button', { name: '이해도 체크 시작' })).toBeVisible()
  await panel(page).getByLabel('체크 시간 (초)').fill('90')
  await panel(page).getByRole('button', { name: '이해도 체크 시작' }).click()

  await expect(panel(page).getByText('이해도 체크가 진행 중입니다.')).toBeVisible()
  expect(state.creates).toEqual([{ type: 'UNDERSTANDING', durationSeconds: 90 }])
  // 진행 중에는 시작 버튼 대신 마감 버튼이 보이고, 방당 진행 중 투표는 하나뿐이다.
  await expect(panel(page).getByRole('button', { name: '이해도 체크 시작' })).toHaveCount(0)

  await panel(page).getByRole('button', { name: '이해도 체크 마감' }).click()
  await expect.poll(() => state.closes).toEqual([700])
  await expect(panel(page).getByRole('button', { name: '이해도 체크 시작' })).toBeVisible()
})

test('제한 시간이 범위를 벗어나면 요청하지 않고 안내한다', async ({ page, context }) => {
  const state = await setup(page, context)
  await page.goto(`/host/rooms/${roomId}`)

  for (const value of ['5', '301', '']) {
    await panel(page).getByLabel('체크 시간 (초)').fill(value)
    await panel(page).getByRole('button', { name: '이해도 체크 시작' }).click()
    await expect(panel(page).getByText('제한 시간은 10~300초로 설정해 주세요.')).toBeVisible()
  }
  expect(state.creates).toEqual([])
})

test('서버가 시작을 거부하면 안내하고 요청은 한 번만 보낸다', async ({ page, context }) => {
  const state = await setup(page, context)
  state.createStatus = 409
  await page.goto(`/host/rooms/${roomId}`)

  await panel(page).getByRole('button', { name: '이해도 체크 시작' }).click()
  await expect(panel(page).getByText('진행 중인 강연에서만 투표를 생성할 수 있습니다.')).toBeVisible()
  expect(state.creates).toHaveLength(1)
})

test('진행 중인 이해도 체크가 있으면 새로고침해도 마감 버튼이 보인다', async ({ page, context }) => {
  const now = new Date()
  await setup(page, context, { open: { pollId: 700, roomId, type: 'UNDERSTANDING', question: '지금 설명을 이해하셨나요?',
    status: 'OPEN', options, createdAt: now.toISOString(), closesAt: new Date(now.getTime() + 60000).toISOString() } })
  await page.goto(`/host/rooms/${roomId}`)

  await expect(panel(page).getByText('이해도 체크가 진행 중입니다.')).toBeVisible()
  await expect(panel(page).getByRole('button', { name: '이해도 체크 마감' })).toBeEnabled()
})

test('이해도 체크를 시작해도 작성 중이던 일반 투표 입력은 지워지지 않는다', async ({ page, context }) => {
  const state = await setup(page, context)
  await page.goto(`/host/rooms/${roomId}`)

  await panel(page).getByLabel('투표 질문').fill('Redis를 쓰는 이유는?')
  await panel(page).getByLabel('선택지 1').fill('캐시')
  await panel(page).getByRole('button', { name: '이해도 체크 시작' }).click()
  await expect(panel(page).getByText('이해도 체크가 진행 중입니다.')).toBeVisible()

  await panel(page).getByRole('button', { name: '이해도 체크 마감' }).click()
  await expect.poll(() => state.closes).toEqual([700])
  await expect(panel(page).getByLabel('투표 질문')).toHaveValue('Redis를 쓰는 이유는?')
  await expect(panel(page).getByLabel('선택지 1')).toHaveValue('캐시')
})

test('서버가 이해도 체크 입력을 거부하면 체크 시간 기준으로 안내한다', async ({ page, context }) => {
  const state = await setup(page, context)
  state.createStatus = 400
  state.createCode = 'INVALID_INPUT'
  await page.goto(`/host/rooms/${roomId}`)

  await panel(page).getByRole('button', { name: '이해도 체크 시작' }).click()
  await expect(panel(page).getByText('체크 시간을 10~300초로 확인해 주세요.')).toBeVisible()
  await expect(panel(page).getByText('질문과 선택지')).toHaveCount(0)
})
