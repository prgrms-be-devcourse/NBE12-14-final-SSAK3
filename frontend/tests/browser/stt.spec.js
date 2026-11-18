import { test, expect } from '@playwright/test'

async function setup(context) {
  const state = { sends: [], keys: [], stopped: false, offline: false, account: 1, gaps: [] }
  await context.addInitScript(() => {
    class SpeechRecognition {
      constructor() { window.testSpeech = this; this.results = [] }
      start() { setTimeout(() => this.onstart?.(), 5) }
      stop() { setTimeout(() => this.onend?.(), 5) }
      abort() { setTimeout(() => this.onend?.(), 0) }
      emit(text, final = true) {
        const result = Object.assign([{ transcript: text }], { isFinal: final })
        if (this.results.length && !this.results.at(-1).isFinal) this.results[this.results.length - 1] = result
        else this.results.push(result)
        this.onresult?.({ resultIndex: this.results.length - 1, results: this.results })
      }
    }
    window.SpeechRecognition = SpeechRecognition
  })
  await context.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const body = route.request().postDataJSON()
    const ok = data => route.fulfill({ json: { data } })
    if (path.endsWith('/auth/me')) return ok({ userId: state.account, name: '문강사', email: 'host@example.test' })
    if (path.endsWith('/auth/login')) return ok({ authenticated: true })
    if (path.endsWith('/stt/segments')) return ok({ roomId: 10, segments: [], gaps: state.gaps })
    if (path.endsWith('/sessions')) {
      state.keys.push(body.startKey)
      return ok({ sttSessionId: 7, status: state.stopped ? 'STOPPED' : 'RECORDING', canRecord: !state.stopped })
    }
    if (path.endsWith('/segments')) {
      state.sends.push(body)
      if (state.offline) return route.abort()
      return ok({ segmentId: state.sends.length })
    }
    if (path.endsWith('/gaps')) {
      state.gaps = [{ gapId: 9, sessionId: 7, startedAt: body.startedAt, endedAt: null }]
      return ok({ gapId: 9 })
    }
    if (path.endsWith('/close')) { state.gaps = []; return ok({ gapId: 9 }) }
    if (path.endsWith('/stop')) { state.stopped = true; return ok({ sttSessionId: 7, status: 'STOPPED', canRecord: false }) }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
  })
  return state
}

async function connect(page) {
  await page.goto('/')
  await page.getByLabel('강연방 ID', { exact: true }).fill('10')
  await page.getByRole('button', { name: '강연방 연결', exact: true }).click()
  await expect(page.getByRole('button', { name: '녹음 시작', exact: true })).toBeEnabled()
}

test('start, interim, final, stop use real UI with server confirmation', async ({ page, context }) => {
  const state = await setup(context)
  await connect(page)
  await page.getByRole('button', { name: '녹음 시작', exact: true }).click()
  await expect(page.locator('.stt-live')).toHaveText('인식 중')
  await page.evaluate(() => window.testSpeech.emit('트랜잭션은', false))
  await expect(page.locator('.stt-interim')).toContainText('트랜잭션은')
  expect(state.sends).toHaveLength(0)
  await page.evaluate(() => window.testSpeech.emit('트랜잭션은 하나의 작업 단위입니다.'))
  await expect.poll(() => state.sends.length).toBe(1)
  expect(state.sends[0].segmentKey).toMatch(/^[0-9a-f-]{36}$/)
  await expect(page.locator('.stt-interim')).toHaveCount(0)
  await page.getByRole('button', { name: '녹음 종료', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('녹음이 종료되었습니다')
  expect(state.stopped).toBe(true)
  await page.screenshot({ path: 'test-results/stt-desktop.png', fullPage: true })
})

test('IndexedDB survives refresh and retry preserves UUID without automatically opening microphone', async ({ page, context }) => {
  const state = await setup(context)
  state.offline = true
  await connect(page)
  await page.getByRole('button', { name: '녹음 시작', exact: true }).click()
  await expect(page.locator('.stt-live')).toBeVisible()
  await page.evaluate(() => window.testSpeech.emit('복구할 발언'))
  await expect.poll(() => state.sends.length).toBeGreaterThan(0)
  const first = state.sends[0]
  state.offline = false
  page.on('dialog', dialog => dialog.accept())
  await page.reload()
  await page.getByLabel('강연방 ID', { exact: true }).fill('10')
  await page.getByRole('button', { name: '강연방 연결', exact: true }).click()
  await expect(page.getByRole('button', { name: '녹음 재개', exact: true })).toBeEnabled()
  await expect.poll(() => state.sends.length).toBeGreaterThan(1)
  expect(state.sends[1]).toEqual(first)
  expect(await page.evaluate(() => !!window.testSpeech)).toBe(false)
})

test('Web Locks prevent second tab recording and allow acquisition after first tab closes', async ({ page, context }) => {
  await setup(context)
  await connect(page)
  await page.getByRole('button', { name: '녹음 시작', exact: true }).click()
  await expect(page.locator('.stt-live')).toBeVisible()
  const second = await context.newPage()
  await second.goto('/')
  await second.getByLabel('강연방 ID', { exact: true }).fill('10')
  await second.getByRole('button', { name: '강연방 연결', exact: true }).click()
  await expect(second.getByRole('status')).toContainText('다른 탭')
  await expect(second.getByRole('button', { name: '녹음 시작', exact: true })).toBeDisabled()
  await page.close({ runBeforeUnload: false })
  await second.getByRole('button', { name: '화면 다시 연결' }).click()
  await expect(second.getByRole('button', { name: '녹음 재개', exact: true })).toBeEnabled()
})

test('mobile layout shows controls without horizontal overflow', async ({ page, context }) => {
  await setup(context)
  await page.setViewportSize({ width: 390, height: 844 })
  await connect(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/stt-mobile.png', fullPage: true })
})
