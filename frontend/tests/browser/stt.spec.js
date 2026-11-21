import { test, expect } from '@playwright/test'
import { setup } from './stt-fixture.js'

async function connect(page) {
  await page.goto('/dev/stt')
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
  expect(state.csrfRequests).toBe(1)
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
  await second.goto('/dev/stt')
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

test('CSRF token is sent with POST and PATCH and refreshed after 403', async ({ page, context }) => {
  const state = await setup(context)
  await page.goto('/dev/stt')

  const login = await page.evaluate(async () => {
    const { request } = await import('/src/stt/api.js')
    return request('/auth/login', {
      method: 'POST',
      body: {
        email: 'host@example.test',
        password: 'ExamplePassword123!',
      },
    })
  })

  expect(login.authenticated).toBe(true)
  expect(state.csrfRequests).toBe(1)

  const closeGap = () => page.evaluate(async () => {
    const { request } = await import('/src/stt/api.js')
    return request('/rooms/10/stt/sessions/7/gaps/9/close', {
      method: 'PATCH',
      body: { endedAt: '2026-11-18T01:00:12.350Z' },
    })
  })

  await closeGap()

  expect(state.csrfRequests).toBe(1)
  expect(state.csrfWrites.map(item => item.method)).toEqual(['POST', 'PATCH'])
  expect(state.csrfWrites.every(item => item.token === 'test-csrf-token')).toBe(true)

  // 세션이 변경되어 이전 토큰이 더는 유효하지 않은 상황을 모의한다.
  state.csrfToken = 'new-csrf-token'

  const failedStatus = await page.evaluate(async () => {
    const { request } = await import('/src/stt/api.js')

    try {
      await request('/rooms/10/stt/sessions/7/gaps/9/close', {
        method: 'PATCH',
        body: { endedAt: '2026-11-18T01:00:12.350Z' },
      })
      return null
    } catch (error) {
      return error.status
    }
  })

  expect(failedStatus).toBe(403)

  // 403 이후 다음 요청에서는 토큰을 다시 발급받는다.
  await closeGap()

  expect(state.csrfRequests).toBe(2)
  expect(state.csrfWrites.map(item => item.method)).toEqual(['POST', 'PATCH', 'PATCH'])
  expect(state.csrfWrites.at(-1).token).toBe('new-csrf-token')
})
