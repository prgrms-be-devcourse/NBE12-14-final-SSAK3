import { test, expect } from '@playwright/test'
import { setupHostRoom } from './host-room-fixture.js'

const roomUrl = '/host/rooms/10'
const reportUrl = roomUrl + '/report'
const reportApi = '/api/v1/rooms/10/reports'
const openReport = page => page.getByRole('link', { name: '회고 보고서 보기', exact: true })
const createReport = page => page.getByRole('button', { name: '보고서 만들기', exact: true })
const returnLink = page => page.getByRole('link', { name: '강연방으로 돌아가기', exact: true })
const reportPoll = 5000
const minute = { startAt: '2026-11-19T01:00:00Z', endAt: '2026-11-19T01:01:00Z' }
const original = '가스 한도와 수수료의 관계를 예로 보여 주세요.'
const summary = '가스 한도와 수수료의 관계에 관한 질문이 있었습니다.'
const content = {
  overview: {
    mainInterests: [{ text: summary, evidence: [{ type: 'CHAT_MESSAGE', id: 901 }], relatedWindows: [minute] }],
    reviewPoints: [], suggestions: [],
  },
  timeline: [{ ...minute, chatMessages: [{ messageId: 901, createdAt: minute.startAt, text: original }] }],
  dataLimitations: [],
}

async function setup(context, status, roomStatus = 'ENDED') {
  const { room, recording } = await setupHostRoom(context, roomStatus)
  const report = {
    status, serverNow: Date.parse(room.endedAt) + 60000, calls: [],
    bodyOffline: false, losePostResponse: false, retryAvailable: false, delayed: false, failureCategory: null,
  }
  await context.route(url => [reportApi, reportApi + '/status', reportApi + '/retry'].includes(url.pathname), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const isStatus = path.endsWith('/status')
    const method = request.method()
    report.calls.push(method + (isStatus ? ' status' : path.endsWith('/retry') ? ' retry' : ' report'))
    const error = (httpStatus, code) => route.fulfill({
      status: httpStatus,
      json: { code, message: code, details: null, timestamp: new Date(report.serverNow).toISOString() },
    })
    if (room.status !== 'ENDED') return error(409, 'ROOM_NOT_ENDED')
    if (method === 'POST') {
      expect(request.headers()['x-csrf-token']).toBe(recording.csrfToken)
      if (path.endsWith('/retry')) {
        expect(report.retryAvailable).toBe(true)
        report.retryAvailable = false
      }
      report.status = 'PENDING'
      if (report.losePostResponse) return route.abort('connectionreset')
      return route.fulfill({ status: 202, json: { data: { roomId: 10, reportId: 601, status: report.status } } })
    }
    if (!isStatus && report.bodyOffline) return route.abort('connectionreset')
    if (report.status === 'REPORT_NOT_FOUND' || report.status === 'ROOM_NOT_FOUND') return error(404, report.status)
    if (report.status === 'FORBIDDEN') return error(403, 'FORBIDDEN')
    if (isStatus) return route.fulfill({ json: { data: {
      status: report.status, retryAvailable: report.retryAvailable, delayed: report.delayed, failureCategory: report.failureCategory,
    } } })
    return route.fulfill({ json: { data: {
      roomId: 10, reportId: 601, status: report.status, createdAt: room.endedAt,
      generatedAt: report.status === 'COMPLETED' ? new Date(report.serverNow).toISOString() : null,
      content: report.status === 'COMPLETED' ? content : null,
    } } })
  })
  return { room, report }
}

for (const [device, viewport] of [
  ['PC', { width: 1280, height: 900 }],
  ['모바일', { width: 390, height: 844 }],
]) {
  test(device + '에서 강연 종료 → 자동 접수 → 본문과 채팅 근거 확인 → 종료 화면 복귀', async ({ page, context }) => {
    const { report } = await setup(context, 'PENDING', 'LIVE')
    await page.setViewportSize(viewport)
    await page.clock.install()
    await page.goto(roomUrl)
    await page.getByRole('button', { name: '강연 종료', exact: true }).click()
    expect(report.calls).toEqual([])
    await page.getByRole('dialog').getByRole('button', { name: '강연 종료', exact: true }).click()
    const status = page.getByText('강연 기록을 정리하고 있어요.', { exact: true })
    await expect(status).toBeVisible()

    const banner = page.getByText('종료된 강연입니다. 새로운 참여는 불가능합니다.')
    const below = device === '모바일' ? page.getByRole('tab', { name: 'AI 요약' })
      : page.getByRole('heading', { name: 'AI 질문·의견 분석' })
    const bounds = await Promise.all([banner, status, below].map(locator => locator.boundingBox()))
    expect(bounds[0].y + bounds[0].height).toBeLessThanOrEqual(bounds[1].y)
    expect(bounds[1].y + bounds[1].height).toBeLessThanOrEqual(bounds[2].y)
    if (device === '모바일') {
      await page.getByRole('tab', { name: '채팅' }).click()
      await expect(status).toBeVisible()
    }
    report.status = 'PROCESSING'
    await page.clock.fastForward(reportPoll + 100)
    await expect(page.getByText('회고 보고서를 만들고 있어요.', { exact: true })).toBeVisible()
    report.status = 'COMPLETED'
    await page.clock.fastForward(reportPoll + 100)
    await expect(openReport(page)).toBeVisible()
    const beforeOpening = [...report.calls]
    await page.clock.fastForward(reportPoll * 2)
    expect(report.calls).toEqual(beforeOpening)
    expect(report.calls.every(call => call === 'GET status')).toBe(true)
    await expect(page).toHaveURL(roomUrl)

    await openReport(page).click()
    await expect(page).toHaveURL(reportUrl)
    await expect(page.getByRole('heading', { name: '강연 진행 실습' })).toBeVisible()
    const card = page.getByRole('article').filter({ hasText: summary })
    await card.locator('summary').click()
    await expect(card.getByText(original, { exact: true })).toBeVisible()
    await card.getByRole('button', { name: /해당 시간대 기록 보기/ }).click()
    await expect(page.locator('#report-record-0-CHAT_MESSAGE-901')).toBeFocused()
    await expect(returnLink(page)).toHaveAttribute('href', roomUrl)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(viewport.width)

    await returnLink(page).click()
    await expect(page).toHaveURL(roomUrl)
    await expect(openReport(page)).toBeVisible()
    expect(report.calls).not.toContain('POST report')
  })
}

test('직접 접속한 보고서에서 연결을 복구하면 본문만 다시 조회한다', async ({ page, context }) => {
  const { report } = await setup(context, 'COMPLETED')
  report.bodyOffline = true
  await page.goto(reportUrl)
  await expect(page.getByText('보고서를 불러오지 못했어요.', { exact: true })).toBeVisible()
  await expect(page).toHaveURL(reportUrl)
  await expect(returnLink(page)).toHaveAttribute('href', roomUrl)
  const retryRequests = []
  page.on('request', req => {
    const path = new URL(req.url()).pathname
    if (path.startsWith('/api/')) retryRequests.push(req.method() + ' ' + path)
  })
  report.bodyOffline = false
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.getByText(summary, { exact: true })).toBeVisible()
  expect(retryRequests).toEqual(['GET ' + reportApi])
})

for (const [status, notice] of [
  ['PENDING', '강연 기록을 정리하고 있어요.'],
  ['PROCESSING', '회고 보고서를 만들고 있어요.'],
  ['FAILED', '보고서 생성에 실패했습니다.'],
  ['REPORT_NOT_FOUND', null],
]) {
  test('직접 접속한 보고서가 ' + status + '이면 같은 강연의 종료 화면에서 안내한다', async ({ page, context }) => {
    const { report } = await setup(context, status)
    if (status === 'FAILED') report.failureCategory = 'AI_OUTPUT'
    await page.goto(reportUrl)
    await expect(page).toHaveURL(roomUrl)
    if (notice) {
      await expect(page.getByText(notice, { exact: true })).toBeVisible()
      await expect(createReport(page)).toHaveCount(0)
    } else {
      await expect(createReport(page)).toBeVisible()
    }
    expect(report.calls).toContain('GET report')
    expect(report.calls).toContain('GET status')
    expect(report.calls).not.toContain('POST report')
    if (status === 'FAILED') {
      await expect(page.getByText(/요약의 형식이나 근거를 확인할 수 없어/)).toBeVisible()
      await expect(page.getByText(/이 보고서는 생성을 다시 요청할 수 없어요/)).toBeVisible()
      await expect(page.getByRole('button', { name: '보고서 생성 다시 시도' })).toHaveCount(0)
    }
  })
}

test('강연이 진행 중이면 보고서 주소에서 자동 생성 안내만 제공한다', async ({ page, context }) => {
  const { report } = await setup(context, 'REPORT_NOT_FOUND', 'LIVE')
  await page.goto(reportUrl)
  await expect(page.getByText('강연이 끝나면 회고 보고서가 자동으로 생성됩니다.', { exact: true })).toBeVisible()
  await expect(createReport(page)).toHaveCount(0)
  await expect(openReport(page)).toHaveCount(0)
  expect(report.calls).not.toContain('POST report')
})

test('수동 접수의 응답이 유실되면 다시 불러오기로 상태만 확인한다', async ({ page, context }) => {
  const { report } = await setup(context, 'REPORT_NOT_FOUND')
  report.losePostResponse = true
  await page.goto(roomUrl)
  await createReport(page).click()
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.getByText('강연 기록을 정리하고 있어요.', { exact: true })).toBeVisible()
  expect(report.calls.filter(call => call === 'POST report')).toHaveLength(1)
  expect(report.calls.at(-1)).toBe('GET status')
})

test('종료 직후 새로고침해도 서버 종료 시각부터 남은 시간만 기다린 뒤 누락 시 한 번 접수한다', async ({ page, context }) => {
  const { room, report } = await setup(context, 'REPORT_NOT_FOUND')
  const endedAt = Date.parse(room.endedAt)
  // 강사의 컴퓨터 시각이 서버보다 하루 빠르더라도 수동 접수를 바로 노출하면 안 된다.
  await page.clock.install({ time: new Date(endedAt + 86400000) })
  await page.clock.pauseAt(new Date(endedAt + 86401000))
  report.serverNow = endedAt
  await page.goto(roomUrl)
  const waiting = page.getByText('보고서 자동 접수를 확인하고 있어요.', { exact: true })
  await expect(waiting).toBeVisible()
  await expect(createReport(page)).toHaveCount(0)
  report.serverNow = endedAt + 1500
  await page.clock.fastForward(1500)
  await page.reload()
  await expect(waiting).toBeVisible()
  await expect(createReport(page)).toHaveCount(0)
  const beforeDeadline = report.calls.length
  report.serverNow = endedAt + 2000
  await page.clock.fastForward(501)
  await expect(createReport(page)).toBeVisible()
  expect(report.calls.slice(beforeDeadline)).toEqual(['GET status'])
  expect(report.calls).not.toContain('POST report')
  await createReport(page).click()
  await expect(page.getByText('강연 기록을 정리하고 있어요.', { exact: true })).toBeVisible()
  await expect(createReport(page)).toHaveCount(0)
  expect(report.calls.filter(call => call === 'POST report')).toHaveLength(1)
})

test('생성 중인 종료 화면은 숨겨지면 조회를 멈추고 다시 보일 때 바로 갱신한다', async ({ page, context }) => {
  const { report } = await setup(context, 'PROCESSING')
  await page.clock.install()
  await page.goto(roomUrl)
  await expect(page.getByText('회고 보고서를 만들고 있어요.', { exact: true })).toBeVisible()
  async function setVisible(visible) {
    await page.evaluate(value => {
      Object.defineProperties(document, {
        hidden: { configurable: true, value: !value },
        visibilityState: { configurable: true, value: value ? 'visible' : 'hidden' },
      })
      document.dispatchEvent(new Event('visibilitychange'))
    }, visible)
  }
  await setVisible(false)
  const beforeHidden = [...report.calls]
  report.status = 'COMPLETED'
  await page.clock.fastForward(reportPoll * 3)
  expect(report.calls).toEqual(beforeHidden)
  await setVisible(true)
  await expect(openReport(page)).toBeVisible()
  expect(report.calls.slice(beforeHidden.length)).toEqual(['GET status'])
})

test('일시 실패만 한 번 직접 재시도하고 응답이 유실되면 상태만 조회한다', async ({ page, context }) => {
  const { report } = await setup(context, 'FAILED')
  report.failureCategory = 'AI_SERVICE'
  const retry = page.getByRole('button', { name: '보고서 생성 다시 시도' })
  await page.goto(roomUrl)
  await expect(page.getByText('보고서 생성에 실패했습니다.', { exact: true })).toBeVisible()
  await expect(page.getByText(/AI 요약을 요청하는 과정에서 문제가 생겨/)).toBeVisible()
  await expect(page.getByText(/이 강연방에 저장된 발언과 채팅, 투표 결과/)).toBeVisible()
  await expect(page.getByText(/이 보고서는 생성을 다시 요청할 수 없어요/)).toBeVisible()
  await expect(retry).toHaveCount(0)
  report.retryAvailable = true
  await page.reload()
  await expect(retry).toBeVisible()
  await expect(page.getByText(/보고서 생성을 한 번 더 요청할 수 있어요/)).toBeVisible()
  expect(report.calls.every(call => call === 'GET status')).toBe(true)
  report.losePostResponse = true
  await retry.click()
  await expect(page.getByText(/AI 요약을 요청하는 과정에서 문제가 생겨/)).toHaveCount(0)
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.getByText('강연 기록을 정리하고 있어요.', { exact: true })).toBeVisible()
  expect(report.calls.filter(call => call === 'POST retry')).toHaveLength(1)
  expect(report.calls.at(-1)).toBe('GET status')
})

test('세부 원인이 없으면 AI 장애를 단정하지 않고 저장된 강연 기록으로 안내한다', async ({ page, context }) => {
  const { report } = await setup(context, 'FAILED')
  const panel = page.getByRole('region', { name: '회고 보고서' })
  for (const category of [null, 'REPORT']) {
    report.failureCategory = category
    await page.goto(roomUrl)
    await expect(panel.getByText(/자세한 실패 원인은 확인되지 않았어요/)).toBeVisible()
    await expect(panel.getByText(/이 강연방에 저장된 발언과 채팅, 투표 결과/)).toBeVisible()
    await expect(panel.getByText(/다시 요청할 수 없어요/)).toBeVisible()
    await expect(panel.getByRole('button')).toHaveCount(0)
  }
  expect(report.calls.every(call => call === 'GET status')).toBe(true)
})

test('결과 확인 중인 요청은 실패로 표시하지 않고 한 번 즉시 재시도할 수 있다', async ({ page, context }) => {
  const { report } = await setup(context, 'FAILED')
  Object.assign(report, { failureCategory: 'UNCONFIRMED', retryAvailable: true })
  await page.clock.install()
  await page.goto(roomUrl)
  const panel = page.getByRole('region', { name: '회고 보고서' })
  await expect(panel.getByRole('status')).toHaveText('보고서 생성 요청의 처리 결과를 확인하고 있어요.')
  await expect(panel.getByText(/이미 접수됐다면 AI가 같은 강연을 두 번/)).toBeVisible()
  await expect(panel).not.toContainText('실패')
  expect(report.calls).toEqual(['GET status'])
  await panel.getByRole('button', { name: '보고서 생성 다시 시도' }).click()
  await expect(panel.getByRole('status')).toHaveText('강연 기록을 정리하고 있어요.')
  await expect(panel.getByRole('button')).toHaveCount(0)
  expect(report.calls).toEqual(['GET status', 'POST retry'])
})

for (const retryAvailable of [true, false]) {
  test('결과 확인 중에는 상태만 다시 조회해 뒤늦게 완료된 보고서를 표시한다 - 재시도 가능 ' + retryAvailable,
    async ({ page, context }) => {
      const { report } = await setup(context, 'FAILED')
      Object.assign(report, { failureCategory: 'UNCONFIRMED', retryAvailable })
      await page.clock.install()
      await page.goto(roomUrl)
      const panel = page.getByRole('region', { name: '회고 보고서' })
      await expect(panel.getByRole('status')).toHaveText('보고서 생성 요청의 처리 결과를 확인하고 있어요.')
      await expect(panel.getByRole('button', { name: '보고서 생성 다시 시도' })).toHaveCount(retryAvailable ? 1 : 0)
      report.status = 'COMPLETED'
      await page.clock.fastForward(reportPoll + 100)
      await expect(openReport(page)).toBeVisible()
      await page.clock.fastForward(reportPoll * 2)
      expect(report.calls).toEqual(['GET status', 'GET status'])
    })
}

for (const retryAvailable of [true, false]) {
  test('30일 동안 접수를 확인하지 못하면 조회를 멈추고 남은 재시도만 제공한다 - 재시도 가능 ' + retryAvailable,
    async ({ page, context }) => {
      const { report } = await setup(context, 'FAILED')
      Object.assign(report, { failureCategory: 'UNCONFIRMED', retryAvailable })
      await page.clock.install()
      await page.goto(roomUrl)
      const panel = page.getByRole('region', { name: '회고 보고서' })
      await expect(panel.getByRole('status')).toContainText('처리 결과를 확인하고 있어요')
      report.failureCategory = 'RECOVERY_EXPIRED'
      await page.clock.fastForward(reportPoll + 100)
      await expect(panel.getByRole('status')).toHaveText('보고서 결과를 확인할 수 있는 기간이 지났어요.')
      await expect(panel.getByText(/30일 동안 이전 요청의 결과/)).toBeVisible()
      const retry = panel.getByRole('button', { name: '보고서 생성 다시 시도' })
      await expect(retry).toHaveCount(retryAvailable ? 1 : 0)
      await page.clock.fastForward(reportPoll * 2)
      expect(report.calls).toEqual(['GET status', 'GET status'])
      if (retryAvailable) {
        await expect(panel.getByText(/같은 강연을 두 번/)).toBeVisible()
        await retry.click()
        await expect(panel.getByRole('status')).toHaveText('강연 기록을 정리하고 있어요.')
      }
    })
}

test('상태 조회에 실패하면 생성 실패로 표시하거나 재요청하지 않는다', async ({ page, context }) => {
  const { report } = await setup(context, 'FAILED')
  report.failureCategory = 'AI_SERVICE'
  report.retryAvailable = true
  await context.route('**/reports/status', route => route.fulfill({ status: 503 }), { times: 1 })
  await page.goto(roomUrl)
  const panel = page.getByRole('region', { name: '회고 보고서' })
  await expect(panel.getByRole('alert')).toContainText('보고서 상태를 조회하지 못했습니다.')
  await expect(panel.getByText(/AI 서비스/)).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '보고서 생성 다시 시도' })).toHaveCount(0)
  await panel.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(panel.getByRole('button', { name: '보고서 생성 다시 시도' })).toBeVisible()
  expect(report.calls).toEqual(['GET status'])
})

test('접수 복구와 장시간 지연을 상태 조회만으로 반영한다', async ({ page, context }) => {
  const { report } = await setup(context, 'REPORT_NOT_FOUND')
  await page.clock.install()
  await page.goto(roomUrl)
  await expect(createReport(page)).toBeVisible()
  report.status = 'PENDING'
  report.delayed = true
  await page.clock.fastForward(30100)
  await expect(page.getByText(/보고서 대기가 2분을 넘었어요/)).toBeVisible()
  report.status = 'PROCESSING'
  await page.clock.fastForward(reportPoll + 100)
  await expect(page.getByText(/보고서 생성을 시작한 지 2분이 지났어요/)).toBeVisible()
  expect(report.calls.every(call => call === 'GET status')).toBe(true)
})

for (const [code, notice] of [
  ['ROOM_NOT_FOUND', '강연방을 찾을 수 없습니다.'],
  ['FORBIDDEN', '이 강연방을 볼 권한이 없습니다.'],
]) {
  test('보고서 주소에서 ' + code + '를 보고서 누락과 구분한다', async ({ page, context }) => {
    const { report } = await setup(context, code)
    await page.goto(reportUrl)
    await expect(page.getByText(notice, { exact: true })).toBeVisible()
    await expect(createReport(page)).toHaveCount(0)
    await expect(page).toHaveURL(reportUrl)
    expect(report.calls).not.toContain('POST report')
  })
}

test('보고서 주소에서 세션이 만료되면 로그인 후 같은 방의 보고서로 돌아온다', async ({ page, context }) => {
  const { report } = await setup(context, 'COMPLETED')
  let authenticated = false
  await context.route(url => ['/api/v1/auth/me', '/api/v1/rooms/10'].includes(url.pathname), route =>
    authenticated ? route.fallback() : route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED' } }))
  await context.route('**/api/v1/auth/login', route => {
    authenticated = true
    return route.fallback()
  })
  await page.goto(reportUrl)
  await expect(page).toHaveURL('/login?returnTo=' + encodeURIComponent(reportUrl))
  await page.getByLabel('이메일').fill('host@example.test')
  await page.getByLabel('비밀번호', { exact: true }).fill('fixture-password')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(reportUrl)
  await expect(page.getByText(summary, { exact: true })).toBeVisible()
  expect(report.calls).toEqual(['GET report'])
})
