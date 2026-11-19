import { test, expect } from '@playwright/test'

const topic = (id, name = `주제 ${id}`) => ({ topicGroupId: id, topic: name, category: 'QUESTION',
  summary: `청중이 ${name}의 실제 사용 예시를 요청했습니다.`, messageCount: 2, sourceMessageIds: [id * 10, id * 10 + 1] })
const analysis = (id, topics = [topic(id)], status = 'COMPLETED') => ({ analysisId: id,
  windowStartAt: new Date(Date.UTC(2026, 10, 19, 1, 0, id * 30)).toISOString(),
  windowEndAt: new Date(Date.UTC(2026, 10, 19, 1, 0, (id + 1) * 30)).toISOString(),
  completedAt: null, status, topicGroups: topics })
const card = (page, name) => page.getByRole('article', { name, exact: true })

async function setup(page) {
  await page.clock.install()
  const state = { analyses: [analysis(2, [topic(21, 'Redis 사용 사례'), topic(22, 'MySQL과의 차이')]), analysis(1)],
    expanded: null, calls: [], sourceCalls: [], error: 0, sourceErrors: {}, authenticated: true, roomError: 0 }
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    const ok = data => route.fulfill({ json: { data } })
    const fail = status => route.fulfill({ status, json: { code: status === 401 ? 'UNAUTHENTICATED' : 'ERROR' } })
    if (path.endsWith('/auth/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    if (path.endsWith('/auth/login')) { state.authenticated = true; state.error = 0; return ok({}) }
    if (path.endsWith('/auth/me')) return state.authenticated ? ok({ userId: 1, name: '강연자' }) : fail(401)
    expect(route.request().method()).toBe('GET')
    if (path === '/api/v1/rooms/10') return state.roomError ? fail(state.roomError)
      : ok({ roomId: 10, title: 'Redis로 이해하는 캐시 전략', status: 'ENDED', createdAt: '2026-11-19T01:00:00Z' })
    if (path.endsWith('/ai/analyses')) {
      state.calls.push(Number(url.searchParams.get('limit')))
      if (state.error === 'offline') return route.abort()
      if (state.error) return fail(state.error)
      return ok({ roomId: 10, analyses: url.searchParams.get('limit') === '50' && state.expanded ? state.expanded : state.analyses,
        lastCompletedAt: null })
    }
    const source = path.match(/topic-groups\/(\d+)\/messages$/)
    if (source) {
      const id = Number(source[1]); state.sourceCalls.push(id)
      if (state.sourceErrors[id]) return fail(state.sourceErrors[id])
      return ok({ topicGroupId: id, topic: `주제 ${id}`, messages: [
        { messageId: id * 10, nickname: '청중 A', content: `실제 사용 예시가 궁금해요 (${id})`, receivedAt: '2026-11-19T01:01:05Z' },
        { messageId: id * 10 + 1, nickname: '청중 B', content: 'MySQL과 함께 쓰는 예시도 보여 주세요.', receivedAt: '2026-11-19T01:01:15Z' },
      ] })
    }
    return fail(404)
  })
  return state
}
async function open(page) {
  await page.goto('/dev/rooms/10/ai')
  await expect(page.getByRole('heading', { name: 'Redis 사용 사례', exact: true })).toBeVisible()
}
async function tick(page) { await page.clock.runFor(5001) }

test('development room link opens real API view; lazy sources reuse cache and open independently', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/10')
  await page.getByRole('link', { name: 'AI 요약 확인', exact: true }).click()
  await expect(card(page, 'Redis 사용 사례')).toBeVisible()
  expect(state.calls).toEqual([10])
  expect(state.sourceCalls).toEqual([])
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await card(page, 'MySQL과의 차이').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (22)')).toBeVisible()
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 접기' }).click()
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  expect(state.sourceCalls).toEqual([21, 22])
  await expect(card(page, 'Redis 사용 사례').getByText('관련 원문 2건')).toBeVisible()
  await expect(card(page, 'Redis 사용 사례').getByText(/10:01:05/)).toBeVisible()
})

test('new summaries wait while reading sources; explicit switch closes sources and keeps history', async ({ page }) => {
  const state = await setup(page); await open(page)
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  state.analyses = [analysis(3), ...state.analyses]
  await tick(page)
  await expect(page.getByRole('button', { name: '새 요약 보기' })).toBeVisible()
  await expect(card(page, 'Redis 사용 사례')).toBeVisible()
  await expect(card(page, '주제 3')).toHaveCount(0)
  await page.getByRole('button', { name: '새 요약 보기' }).click()
  await expect(card(page, '주제 3')).toBeVisible()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toHaveCount(0)
  await page.getByRole('button', { name: '이전 분석 보기' }).click()
  await expect(card(page, 'Redis 사용 사례')).toBeVisible()
  await expect(page.getByRole('button', { name: '원문 접기' })).toHaveCount(0)
})

test('same topic in different intervals stays separate and more expands request to 50', async ({ page }) => {
  const state = await setup(page)
  state.analyses = [analysis(3, [topic(31, 'Redis')]), analysis(2, [topic(21, 'Redis')])]
  state.expanded = [...state.analyses, analysis(1)]
  await page.goto('/dev/rooms/10/ai')
  await expect(card(page, 'Redis').first()).toBeVisible()
  await page.getByRole('button', { name: '이전 분석 보기' }).click()
  await expect(card(page, 'Redis')).toHaveCount(2)
  await page.getByRole('button', { name: '더 보기', exact: true }).click()
  await expect(card(page, '주제 1')).toBeVisible()
  expect(state.calls).toEqual([10, 50])
  await expect(page.getByText('최근 50개 구간까지 조회합니다.', { exact: false })).toBeVisible()
})

test('empty initial ten falls back to fifty and finds last completed summary', async ({ page }) => {
  const state = await setup(page)
  state.analyses = [analysis(20, [])]
  state.expanded = [analysis(20, []), analysis(1, [topic(1, '예전 요약')])]
  await page.goto('/dev/rooms/10/ai')
  await expect(card(page, '예전 요약')).toBeVisible()
  await expect(page.getByRole('heading', { name: '마지막 요약', exact: true })).toBeVisible()
  expect(state.calls).toEqual([10, 50])
  await page.reload()
  await expect(card(page, '예전 요약')).toBeVisible()
  expect(state.calls).toEqual([10, 50, 10, 50])
})

test('more reveals older history without losing open sources; collapsing history releases the reading hold', async ({ page }) => {
  const state = await setup(page); await open(page)
  state.expanded = [...state.analyses, analysis(0)]
  await page.getByRole('button', { name: '이전 분석 보기' }).click()
  await card(page, '주제 1').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (1)')).toBeVisible()
  await page.getByRole('button', { name: '더 보기', exact: true }).click()
  await expect(card(page, '주제 0')).toBeVisible()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (1)')).toBeVisible()
  state.expanded = [analysis(3), ...state.expanded]; await tick(page)
  await expect(page.getByRole('button', { name: '새 요약 보기' })).toBeVisible()
  await page.getByRole('button', { name: '이전 분석 접기' }).click()
  await expect(page.getByRole('region', { name: '최신 요약 3', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '새 요약 보기' })).toHaveCount(0)
})

test('initial connection failure can retry and source cache is not retained across refresh', async ({ page }) => {
  const state = await setup(page); state.error = 500
  await page.goto('/dev/rooms/10/ai')
  await expect(page.getByRole('button', { name: '다시 연결' })).toBeVisible()
  await expect(page.getByText('최근 조회 범위에 표시할 요약이 없습니다.')).toHaveCount(0)
  state.error = 0
  await page.getByRole('button', { name: '다시 연결' }).click()
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  await page.reload()
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  expect(state.sourceCalls).toEqual([21, 21])
})

test('no analyses and empty completed intervals are distinguished without claiming lecture-wide absence', async ({ page }) => {
  const state = await setup(page); state.analyses = []
  await page.goto('/dev/rooms/10/ai')
  await expect(page.getByText('아직 분석 기록이 없습니다.')).toBeVisible()
  await expect(page.getByText('최근 조회 범위에 표시할 요약이 없습니다.')).toBeVisible()
  state.analyses = [analysis(2, [])]; await tick(page)
  await expect(page.getByText('이 구간에는 표시할 새 요약이 없습니다.').first()).toBeVisible()
  await expect(page.getByText('아직 분석 기록이 없습니다.')).toHaveCount(0)
})

test('new empty, pending, processing and failed intervals preserve last summary and its time', async ({ page }) => {
  const state = await setup(page); await open(page)
  const statuses = [['COMPLETED', '이 구간에는 표시할 새 요약이 없습니다.'],
    ['PENDING', '새 청중 의견 분석을 기다리고 있습니다.'], ['PROCESSING', '새 청중 의견을 분석하고 있습니다.'],
    ['FAILED', '이 구간의 의견을 요약하지 못했습니다.']]
  for (const [status, message] of statuses) {
    state.analyses = [analysis(3, [], status)]; await tick(page)
    await expect(page.locator('.ai-status')).toContainText(message)
    await expect(card(page, 'Redis 사용 사례')).toBeVisible()
    await expect(page.getByRole('region', { name: '마지막 요약 2', exact: true })).toContainText('10:01:00')
  }
})

test('polling keeps running after lecture end, pauses while hidden and resumes immediately', async ({ page }) => {
  const state = await setup(page); await open(page)
  await tick(page); await expect.poll(() => state.calls.length).toBe(2)
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.clock.runFor(15000); expect(state.calls.length).toBe(2)
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => state.calls.length).toBe(3)
  await page.getByRole('link', { name: '← 강연방 상세로 돌아가기' }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
  const count = state.calls.length; await page.clock.runFor(15000)
  expect(state.calls.length).toBe(count)
})

for (const error of [500, 'offline']) test(`list error ${error} retains evidence and stops polling until reconnect`, async ({ page }) => {
  const state = await setup(page); await open(page)
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  state.error = error; await tick(page)
  await expect(page.getByRole('button', { name: '다시 연결' })).toBeVisible()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  const count = state.calls.length; await page.clock.runFor(15000); expect(state.calls.length).toBe(count)
  state.error = 0; await page.getByRole('button', { name: '다시 연결' }).click()
  await expect(page.getByRole('button', { name: '다시 연결' })).toHaveCount(0)
  await tick(page); await expect.poll(() => state.calls.length).toBe(count + 2)
})

test('source failure retries only that source and source 404 leaves other summaries intact', async ({ page }) => {
  const state = await setup(page); state.sourceErrors = { 21: 500, 22: 404 }; await open(page)
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('원문을 불러오지 못했습니다.')).toBeVisible()
  state.sourceErrors[21] = 0
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  await card(page, 'MySQL과의 차이').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('근거 원문을 찾을 수 없습니다.')).toBeVisible()
  await expect(card(page, 'Redis 사용 사례')).toBeVisible()
  expect(state.calls).toEqual([10])
})

for (const status of [403, 404]) test(`list ${status} clears private content and stops polling`, async ({ page }) => {
  const state = await setup(page); await open(page)
  state.error = status; await tick(page)
  await expect(page.getByRole('heading', { name: status === 403 ? '이 강연방을 조회할 권한이 없습니다' : '강연방을 찾을 수 없습니다' })).toBeVisible()
  await expect(page.getByText('Redis 사용 사례', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Redis로 이해하는 캐시 전략')).toHaveCount(0)
  const count = state.calls.length; await page.clock.runFor(15000); expect(state.calls.length).toBe(count)
})

test('source authorization failure clears the entire screen', async ({ page }) => {
  const state = await setup(page); state.sourceErrors[21] = 403; await open(page)
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByRole('heading', { name: '이 강연방을 조회할 권한이 없습니다' })).toBeVisible()
  await expect(card(page, 'Redis 사용 사례')).toHaveCount(0)
})

test('expired login returns to the exact AI room route after authentication', async ({ page }) => {
  const state = await setup(page); await open(page)
  state.authenticated = false; state.error = 401; await tick(page)
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fdev%2Frooms%2F10%2Fai$/)
  await page.getByLabel('이메일', { exact: true }).fill('host@example.com')
  await page.getByLabel('비밀번호', { exact: true }).fill('password123')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/dev\/rooms\/10\/ai$/)
  await expect(card(page, 'Redis 사용 사례')).toBeVisible()
})

test('desktop and mobile cards display long source text safely without horizontal overflow', async ({ page }, testInfo) => {
  const state = await setup(page)
  state.analyses[0].topicGroups[0].summary += '\n<script>alert("not executable")</script> ' + '긴문장'.repeat(30)
  await page.setViewportSize({ width: 1440, height: 1100 }); await open(page)
  await card(page, 'Redis 사용 사례').getByRole('button', { name: '원문 보기' }).click()
  await expect(page.getByText('실제 사용 예시가 궁금해요 (21)')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('ai-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await expect(card(page, 'Redis 사용 사례')).toContainText('<script>alert("not executable")</script>')
  await page.screenshot({ path: testInfo.outputPath('ai-mobile.png'), fullPage: true })
})
