import { test, expect } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

const roomUrl = '/host/rooms/10'
const button = (page, name) => page.getByRole('button', { name, exact: true })
const ended = page => page.getByText('종료된 강연입니다. 새로운 참여는 불가능합니다.')

async function setup(context, status = 'LIVE') {
  const recording = await setupRecording(context)
  await context.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      if (frame.toString().startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
    })
  })
  await context.route(url => url.pathname === '/api/v1/rooms/home', route =>
    route.fulfill({ json: { data: { liveRooms: [], readyRooms: { rooms: [], nextCursor: null } } } }))
  const room = { status, failDetail: false, start: 'ok', end: 'ok', events: [], records: null, messages: [] }
  await context.route('**/api/v1/rooms/10{,/**}', async route => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace('/api/v1/rooms/10', '')
    room.events.push(req.method() + ' ' + path)
    const ok = data => route.fulfill({ json: { data } })
    if (path === '') {
      if (room.failDetail) return route.abort()
      return ok({ roomId: 10, title: '강연 진행 실습', status: room.status, createdAt: '2026-11-19T00:00:00Z',
        startedAt: room.status === 'READY' ? null : '2026-11-19T01:00:00Z',
        endedAt: room.status === 'ENDED' ? '2026-11-19T02:00:00Z' : null })
    }
    if (path === '/start' || path === '/end') {
      expect(req.method()).toBe('POST')
      expect(req.headers()['x-csrf-token']).toBe(recording.csrfToken)
      const outcome = room[path.slice(1)]
      if (outcome === 'lost-before') return route.abort()
      room.status = path === '/start' ? 'LIVE' : 'ENDED'
      if (outcome === 'lost-after') return route.abort()
      const timeField = path === '/start' ? 'startedAt' : 'endedAt'
      return ok({ roomId: 10, status: room.status, [timeField]: '2026-11-19T01:00:00Z' })
    }
    if (path === '/invitation') return ok({ joinCode: 'ABC123', joinUrl: 'https://example.test/join/ABC123',
      qrText: 'https://example.test/join/ABC123' })
    if (path === '/ai/analyses') return ok({ roomId: 10, analyses: [] })
    if (path === '/messages') return ok({ messages: room.messages, nextCursor: null, hasNext: false })
    if (path === '/stt/segments' && room.records) return ok(room.records)
    return route.fallback()
  })
  return { room, recording }
}

async function record(page) {
  await button(page, '녹음 시작').click()
  await expect(page.locator('.stt-live')).toHaveText('인식 중')
}

async function confirmEnd(page) {
  await button(page, '강연 종료').click()
  await button(page.getByRole('dialog'), '강연 종료').click()
}

test('모바일에서 AI 요약과 채팅을 오가도 녹음과 입력이 유지되고 PC에서는 두 패널을 나란히 표시한다', async ({ page, context }) => {
  const { room, recording } = await setup(context)
  room.messages = Array.from({ length: 24 }, (_, index) => ({
    messageId: index + 1, sender: { type: 'PARTICIPANT', id: 3, name: '수강생' },
    content: `질문 ${index + 1}`, receivedAt: '2026-11-19T01:10:00Z',
  }))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(roomUrl)
  const ai = page.getByRole('heading', { name: 'AI 질문·의견 분석' })
  const chat = page.getByRole('region', { name: '강연 채팅' })
  const aiTab = page.getByRole('tab', { name: 'AI 요약' })
  const chatTab = page.getByRole('tab', { name: '채팅' })
  await expect(aiTab).toHaveAttribute('aria-selected', 'true')
  await expect(ai).toBeVisible()
  await expect(chat).toBeHidden()
  await record(page)
  await chatTab.click()
  const message = chat.getByRole('textbox', { name: '메시지', exact: true })
  await message.fill('작성 중인 질문')
  const log = chat.getByRole('log')
  await expect(log.getByText('질문 24', { exact: true })).toBeVisible()
  await log.evaluate(el => { el.scrollTop = 120 })
  await expect.poll(() => log.evaluate(el => el.scrollTop)).toBe(120)
  await expect(ai).toBeHidden()
  await expect(page.getByText('기록 저장', { exact: true })).toBeVisible()
  await expect(button(page, '녹음 중지')).toBeVisible()
  await chatTab.press('ArrowLeft')
  await expect(aiTab).toBeFocused()
  await expect(ai).toBeVisible()
  await aiTab.press('ArrowRight')
  await expect(chatTab).toBeFocused()
  await expect(message).toHaveValue('작성 중인 질문')
  await expect.poll(() => log.evaluate(el => el.scrollTop)).toBe(120)
  await page.evaluate(() => window.testSpeech.emit('탭을 바꿔도 저장되는 발언'))
  await expect.poll(() => recording.sends.map(s => s.transcript)).toContain('탭을 바꿔도 저장되는 발언')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)

  await page.setViewportSize({ width: 1280, height: 900 })
  await expect(chatTab).toHaveCount(0)
  await expect(ai).toBeVisible()
  await expect(message).toHaveValue('작성 중인 질문')
  const analysisBounds = await page.locator('#room-panel-ai').boundingBox()
  const chatBounds = await page.locator('#room-panel-chat').boundingBox()
  expect(analysisBounds.y).toBe(chatBounds.y)
  expect(analysisBounds.x + analysisBounds.width).toBeLessThan(chatBounds.x)
})

test('lost start response reads room before retry; confirmed LIVE never restarts or opens mic', async ({ page, context }) => {
  const { room, recording } = await setup(context, 'READY')
  await page.goto(roomUrl)
  await expect(button(page, '강연 시작')).toBeEnabled()
  room.start = 'lost-before'
  room.failDetail = true
  await button(page, '강연 시작').click()
  await expect(button(page, '상태 다시 확인')).toBeVisible()
  await expect(button(page, '강연 시작 다시 시도')).toHaveCount(0)
  room.failDetail = false
  await button(page, '상태 다시 확인').click()
  await expect(button(page, '강연 시작 다시 시도')).toBeEnabled()
  room.start = 'lost-after'
  await button(page, '강연 시작 다시 시도').click()
  await expect(button(page, '녹음 시작')).toBeEnabled()
  await expect(page.getByRole('heading', { name: 'AI 질문·의견 분석' })).toBeVisible()
  expect(room.events.filter(e => e === 'POST /start')).toHaveLength(2)
  expect(recording.keys).toHaveLength(0)
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
})

test('dialog keeps recording; final emitted on microphone stop is saved before STT and room end', async ({ page, context }) => {
  const { room, recording } = await setup(context)
  await page.goto(roomUrl)
  await record(page)
  await button(page, '강연 종료').click()
  await page.evaluate(() => window.testSpeech.emit('확인 중에도 기록합니다.'))
  await expect.poll(() => recording.sends.length).toBe(1)
  await button(page, '계속 진행').click()
  await expect(page.locator('.stt-live')).toHaveText('인식 중')
  await page.evaluate(() => {
    const speech = window.testSpeech
    const stop = speech.stop.bind(speech)
    speech.stop = () => { speech.emit('마지막으로 확정된 발언입니다.'); stop() }
  })
  await confirmEnd(page)
  await expect(ended(page)).toBeVisible()
  expect(recording.sends.map(s => s.transcript)).toEqual(['확인 중에도 기록합니다.', '마지막으로 확정된 발언입니다.'])
  expect(room.events.filter(e => e.startsWith('POST') && !e.endsWith('/sessions'))).toEqual([
    'POST /stt/sessions/7/segments', 'POST /stt/sessions/7/segments', 'POST /stt/sessions/7/stop', 'POST /end',
  ])
})

for (const intent of ['녹음 중지', '강연 종료']) {
  test(intent + ' 저장 실패 후 같은 발언을 재전송하고 선택한 범위만 종료', async ({ page, context }) => {
    const { room, recording } = await setup(context)
    await page.clock.install()
    await page.goto(roomUrl)
    await record(page)
    recording.offline = true
    await page.evaluate(() => window.testSpeech.emit('저장할 마지막 발언'))
    await expect.poll(() => recording.sends.length).toBeGreaterThan(0)
    if (intent === '강연 종료') await confirmEnd(page)
    else await button(page, intent).click()
    const retry = button(page, intent === '강연 종료' ? '재시도 후 강연 종료' : '저장 재시도')
    await expect(retry).toBeEnabled()
    // Keep the API offline through the automatic attempts. Only the manual retry can succeed.
    await expect.poll(async () => {
      await page.clock.fastForward(17000)
      return page.getByText('저장 재시도 필요', { exact: true }).isVisible()
    }, { timeout: 12000, intervals: [100, 200] }).toBe(true)
    expect(room.status).toBe('LIVE')
    expect(recording.stopped).toBe(false)
    recording.offline = false
    await retry.click()
    await expect.poll(() => recording.stopped).toBe(true)
    expect(new Set(recording.sends.map(s => s.segmentKey)).size).toBe(1)
    if (intent === '강연 종료') await expect(ended(page)).toBeVisible()
    else {
      await expect(page.getByText('녹음이 종료되었습니다.')).toBeVisible()
      expect(room.events).not.toContain('POST /end')
    }
  })
}

test('reload recovers pending speech without closing LIVE until host continues', async ({ page, context }) => {
  const { room, recording } = await setup(context)
  await page.goto(roomUrl)
  await record(page)
  recording.offline = true
  await page.evaluate(() => window.testSpeech.emit('다시 연결 후 복구할 발언'))
  await expect.poll(() => recording.sends.length).toBeGreaterThan(0)
  await confirmEnd(page)
  await expect(button(page, '재시도 후 강연 종료')).toBeEnabled()
  const pending = recording.sends[0]
  page.on('dialog', dialog => dialog.accept())
  recording.offline = false
  await page.reload()
  await expect(button(page, '강연 종료 계속하기')).toBeEnabled()
  await expect.poll(() => recording.stopped).toBe(true)
  expect(room.events).not.toContain('POST /end')
  expect(recording.sends.at(-1)).toEqual(pending)
  expect(await page.evaluate(() => !!window.testSpeech)).toBe(false)
  await button(page, '강연 종료 계속하기').click()
  await expect(ended(page)).toBeVisible()
  expect(room.events.filter(e => e === 'POST /stt/sessions/7/stop')).toHaveLength(1)
})

test('uncertain end checks status; only room end is retried and committed response loss is recovered', async ({ page, context }) => {
  const { room } = await setup(context)
  await page.goto(roomUrl)
  await record(page)
  room.end = 'lost-before'
  room.failDetail = true
  await confirmEnd(page)
  await expect(button(page, '상태 다시 확인')).toBeVisible()
  room.failDetail = false
  await button(page, '상태 다시 확인').click()
  await expect(button(page, '강연 종료 다시 시도')).toBeVisible()
  room.end = 'lost-after'
  await button(page, '강연 종료 다시 시도').click()
  await expect(ended(page)).toBeVisible()
  expect(room.events.filter(e => e === 'POST /end')).toHaveLength(2)
  expect(room.events.filter(e => e === 'POST /stt/sessions/7/stop')).toHaveLength(1)
})

test('unused microphone stays off and an unsupported recording browser can close an empty room', async ({ page, context }) => {
  const { room } = await setup(context)
  await page.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: undefined }))
  await page.goto(roomUrl)
  await expect(button(page, '녹음 시작')).toBeDisabled()
  await confirmEnd(page)
  await expect(ended(page)).toBeVisible()
  expect(room.events.filter(e => e.startsWith('POST'))).toEqual(['POST /end'])
})

test('mobile and desktop LIVE show recording status and AI while recent speech stays folded', async ({ page, context }, testInfo) => {
  await setup(context)
  await page.goto(roomUrl)
  await record(page)
  await page.evaluate(() => window.testSpeech.emit('어떤 설명을 더 듣고 싶으신가요?'))
  const transcript = page.getByText('어떤 설명을 더 듣고 싶으신가요?')
  for (const [width, height] of [[1440, 1000], [390, 844]]) {
    await page.setViewportSize({ width, height })
    await expect(transcript).toBeHidden()
    await expect(page.getByRole('heading', { name: 'AI 질문·의견 분석' })).toBeVisible()
    await expect(button(page, '녹음 중지')).toBeEnabled()
    await expect(page.getByText('서버 저장 완료', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('room-live-' + width + '.png'), fullPage: true })
    await page.getByText('최근 발언 보기', { exact: true }).click()
    await expect(transcript).toBeVisible()
    await page.getByText('최근 발언 보기', { exact: true }).click()
  }
})

test('ended reload shows room-scoped chronological records on demand and keeps AI available', async ({ page, context }) => {
  const { room } = await setup(context, 'ENDED')
  await page.addInitScript(() => sessionStorage.setItem('react:host-end:1:10', 'end'))
  const at = min => '2026-11-19T01:' + min + ':00Z'
  room.records = { roomId: 10, segments: [
    { segmentId: 2, sessionId: 8, segmentStartAt: at('12'), transcript: '두 번째 세션' },
    { segmentId: 1, sessionId: 7, segmentStartAt: at('03'), transcript: '첫 발언' },
  ], gaps: [{ gapId: 1, sessionId: 7, startedAt: at('05'), endedAt: at('06'), reason: 'ERROR' }] }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(roomUrl)
  await expect(page.getByRole('heading', { name: 'AI 질문·의견 분석' })).toBeVisible()
  await expect(button(page, '녹음 시작')).toHaveCount(0)
  await expect(page.getByLabel('참여 링크')).toHaveCount(0)
  expect(room.events).not.toContain('GET /stt/segments')
  await button(page, '저장된 발언 보기').click()
  const rows = page.getByRole('list', { name: '저장된 발언과 누락 구간' }).getByRole('listitem')
  await expect(rows).toHaveText([/10:03:00.*첫 발언/, /10:05:00.*10:06:00.*전사 누락/, /10:12:00.*두 번째 세션/])
  await button(page, '저장된 발언 접기').click()
  await button(page, '저장된 발언 보기').click()
  expect(room.events.filter(e => e === 'GET /stt/segments')).toHaveLength(1)
  expect(room.events).not.toContain('POST /end')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

async function selectHostMenu(page, name) {
  if (page.viewportSize().width <= 760) await button(page, '메뉴 열기').click()
  await button(page, name).click()
}

async function emitFinalWhileStopping(page, transcript) {
  await page.evaluate(text => {
    const speech = window.testSpeech
    const stop = speech.stop.bind(speech)
    speech.stop = () => setTimeout(() => { speech.emit(text); stop() }, 0)
  }, transcript)
}

// Simulate a quota error only for snapshots containing a pending final.
async function rejectPendingWrites(page) {
  await page.evaluate(() => {
    window.rejectSttWrites = true
    const put = IDBObjectStore.prototype.put
    IDBObjectStore.prototype.put = function (snapshot, key) {
      if (this.name === 'recordings' && window.rejectSttWrites && snapshot.queue.some(item => item.kind === 'segment')) {
        throw new DOMException('Storage quota exceeded', 'QuotaExceededError')
      }
      return put.call(this, snapshot, key)
    }
  })
}

async function pendingTranscripts(page) {
  return page.evaluate(async () => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open('react-stt')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    try {
      const snapshot = await new Promise((resolve, reject) => {
        const request = db.transaction('recordings').objectStore('recordings').get('1:10')
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      })
      return snapshot.queue.filter(item => item.kind === 'segment').map(item => item.body.transcript)
    } finally { db.close() }
  })
}

for (const [width, height, destination] of [[1440, 1000, '홈'], [390, 844, '새 강연방']]) {
  test(width + 'px 초대·이동 확인 중에도 녹음하고 마지막 발언을 로컬에 남긴 뒤 선택한 메뉴로 이동', async ({ page, context }) => {
    const { room, recording } = await setup(context)
    await page.setViewportSize({ width, height })
    await page.goto(roomUrl)
    await record(page)

    await selectHostMenu(page, '청중 초대')
    await expect(page.getByRole('dialog', { name: '청중 초대' })).toBeVisible()
    await page.evaluate(() => window.testSpeech.emit('초대 메뉴에서도 들리는 발언'))
    await expect.poll(() => recording.sends.map(s => s.transcript)).toContain('초대 메뉴에서도 들리는 발언')
    await button(page, '청중 초대 닫기').click()

    await selectHostMenu(page, destination)
    const confirm = page.getByRole('dialog', { name: '녹음 중인 강연에서 나갈까요?' })
    await expect(confirm).toBeVisible()
    await page.evaluate(() => window.testSpeech.emit('이동 확인 중에도 들리는 발언'))
    await expect.poll(() => recording.sends.map(s => s.transcript)).toContain('이동 확인 중에도 들리는 발언')
    await button(confirm, '계속 녹음하기').click()
    await expect(page.locator('.stt-live')).toHaveText('인식 중')

    const last = '이동 직전에 늦게 확정된 발언'
    recording.offline = true
    await emitFinalWhileStopping(page, last)
    await selectHostMenu(page, destination)
    await button(confirm, '이동하기').click()
    await expect(page).toHaveURL(/\/host\/rooms\/new$/)
    const focusTarget = destination === '홈' ? page.getByRole('region', { name: '진행 중인 내 강연' }) : page.getByLabel(/강연 제목/)
    await expect(focusTarget).toBeFocused()
    expect(await page.evaluate(() => !!window.testSpeech)).toBe(true) // Same document after navigation.
    expect(await pendingTranscripts(page)).toContain(last)
    expect(recording.stopped).toBe(false)
    expect(room.events).not.toContain('POST /end')
  })
}

test('서버 저장을 기다리다가 머무르면 늦은 성공 이후에도 방에 머물고 수동으로 녹음을 재개', async ({ page, context }) => {
  const { room, recording } = await setup(context)
  let allowResponse
  const gate = new Promise(resolve => { allowResponse = resolve })
  await page.route('**/stt/sessions/*/segments', async route => { await gate; await route.fallback() })
  await page.goto(roomUrl)
  await record(page)
  await rejectPendingWrites(page)
  await emitFinalWhileStopping(page, '서버 저장이 늦는 발언')
  await selectHostMenu(page, '홈')
  const sending = page.waitForRequest(req => req.method() === 'POST' && new URL(req.url()).pathname.endsWith('/segments'))
  await button(page, '이동하기').click()
  try {
    await sending
    const waiting = page.getByRole('dialog', { name: '마지막 발언 저장 확인 중' })
    await button(waiting, '강연에 머무르기').click()
    await expect(waiting).toHaveCount(0)
  } finally { allowResponse() }
  await expect.poll(() => recording.sends.map(s => s.transcript)).toContain('서버 저장이 늦는 발언')
  await expect(page.getByText('서버 저장 완료', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
  await expect(page.getByText('인식 중단', { exact: true })).toBeVisible()
  await button(page, '녹음 재개').click()
  await expect(page.locator('.stt-live')).toHaveText('인식 중')
  expect(recording.stopped).toBe(false)
  expect(room.events).not.toContain('POST /end')
})

test('서버와 브라우저 모두 저장에 실패하면 이동을 보류하고 저장 재시도로 원래 선택한 새 강연방에 진입', async ({ page, context }) => {
  const { room, recording } = await setup(context)
  await page.goto(roomUrl)
  await record(page)
  await rejectPendingWrites(page)
  recording.offline = true
  const last = '두 저장소 모두 실패한 발언'
  await emitFinalWhileStopping(page, last)
  await selectHostMenu(page, '새 강연방')
  await button(page, '이동하기').click()
  await expect.poll(() => recording.sends.map(s => s.transcript)).toContain(last)
  const waiting = page.getByRole('dialog', { name: '마지막 발언 저장 확인 중' })
  await expect(waiting).toBeVisible()
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
  const retry = button(waiting, '저장 재시도')
  await retry.evaluate(el => el.addEventListener('click', () => { window.rejectSttWrites = false }, { capture: true, once: true }))
  await retry.click()
  await expect(page).toHaveURL(/\/host\/rooms\/new$/)
  await expect(page.getByLabel(/강연 제목/)).toBeFocused()
  expect(await pendingTranscripts(page)).toContain(last)
  expect(recording.stopped).toBe(false)
  expect(room.events).not.toContain('POST /end')
})
